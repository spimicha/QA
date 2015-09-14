package edu.stanford.nlp.naturalli;

import edu.stanford.nlp.ie.machinereading.structure.Span;
import edu.stanford.nlp.ie.util.RelationTriple;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.ling.tokensregex.TokenSequenceMatcher;
import edu.stanford.nlp.ling.tokensregex.TokenSequencePattern;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.semgraph.semgrex.SemgrexMatcher;
import edu.stanford.nlp.semgraph.semgrex.SemgrexPattern;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.util.*;
import edu.stanford.nlp.util.PriorityQueue;

import java.util.*;
import java.util.stream.Collectors;

/**
 * This class takes a {@link edu.stanford.nlp.naturalli.SentenceFragment} and converts it to a conventional
 * OpenIE triple, as materialized in the {@link RelationTriple} class.
 *
 * @author Gabor Angeli
 */
public class RelationTripleSegmenter {

  private final boolean allowNominalsWithoutNER;

  /** A list of patterns to match relation extractions against */
  private List<SemgrexPattern> VERB_PATTERNS = Collections.unmodifiableList(new ArrayList<SemgrexPattern>() {{
    // { blue cats play [quietly] with yarn,
    //   Jill blew kisses at Jack,
    //   cats are standing next to dogs }
    add(SemgrexPattern.compile("{$}=verb ?>/cop|aux(pass)?/ {}=be >/.subj(pass)?/ {}=subject >/(nmod|acl|advcl):.*/=prepEdge ( {}=object ?>appos {} = appos ) ?>dobj {pos:/N.*/}=relObj"));
    // { fish like to swim }
    add(SemgrexPattern.compile("{$}=verb >/.subj(pass)?/ {}=subject >xcomp ( {}=object ?>appos {}=appos )"));
    // { cats have tails }
    add(SemgrexPattern.compile("{$}=verb ?>/aux(pass)?/ {}=be >/.subj(pass)?/ {}=subject >/[di]obj|xcomp/ ( {}=object ?>appos {}=appos )"));
    // { cats are cute,
    //   horses are grazing peacefully }
    add(SemgrexPattern.compile("{$}=object >/.subj(pass)?/ {}=subject >/cop|aux(pass)?/ {}=verb"));
    // { Tom and Jerry were fighting }
    add(SemgrexPattern.compile("{$}=verb >nsubjpass ( {}=subject >/conj:and/=subjIgnored {}=object )"));
  }});

  /**
   * A set of nominal patterns, that don't require being in a coherent clause, but do require NER information.
   */
  private final List<TokenSequencePattern> NOUN_TOKEN_PATTERNS = Collections.unmodifiableList(new ArrayList<TokenSequencePattern>() {{
    // { NER nominal_verb NER,
    //   United States president Obama }
    add(TokenSequencePattern.compile("(?$object [ner:/PERSON|ORGANIZATION|LOCATION+/]+ ) (?$beof_comp [ {tag:/NN.*/} & !{ner:/PERSON|ORGANIZATION|LOCATION/} ]+ ) (?$subject [ner:/PERSON|ORGANIZATION|LOCATION/]+ )"));
    // { NER 's nominal_verb NER,
    //   America 's president , Obama }
    add(TokenSequencePattern.compile("(?$object [ner:/PERSON|ORGANIZATION|LOCATION+/]+ ) /'s/ (?$beof_comp [ {tag:/NN.*/} & !{ner:/PERSON|ORGANIZATION|LOCATION/} ]+ ) /,/? (?$subject [ner:/PERSON|ORGANIZATION|LOCATION/]+ )"));
    // { NER , NER ,,
    //   Obama, 28, ...,
    //   Obama (28) ...}
    add(TokenSequencePattern.compile("(?$subject [ner:/PERSON|ORGANIZATION|LOCATION/]+ ) /,/ (?$object [ner:/NUMBER|DURATION|PERSON|ORGANIZATION/]+ ) /,/"));
    add(TokenSequencePattern.compile("(?$subject [ner:/PERSON|ORGANIZATION|LOCATION/]+ ) /\\(/ (?$object [ner:/NUMBER|DURATION|PERSON|ORGANIZATION/]+ ) /\\)/"));
  }});

  /**
   * A set of nominal patterns using dependencies, that don't require being in a coherent clause, but do require NER information.
   */
  private final List<SemgrexPattern> NOUN_DEPENDENCY_PATTERNS;


  /**
   * Create a new relation triple segmenter.
   *
   * @param allowNominalsWithoutNER If true, extract all nominal relations and not just those which are warranted based on
   *                                named entity tags. For most practical applications, this greatly over-produces trivial triples.
   */
  public RelationTripleSegmenter(boolean allowNominalsWithoutNER) {
    this.allowNominalsWithoutNER = allowNominalsWithoutNER;
    NOUN_DEPENDENCY_PATTERNS = Collections.unmodifiableList(new ArrayList<SemgrexPattern>() {{
      // { Durin, son of Thorin }
      add(SemgrexPattern.compile("{tag:/N.*/}=subject >appos ( {}=relation >/nmod:.*/=relaux {}=object)"));
      // { Thorin's son, Durin }
      add(SemgrexPattern.compile("{}=relation >/nmod:.*/=relaux {}=subject >appos {}=object"));
      //  { President Obama }
      if (allowNominalsWithoutNER) {
        add(SemgrexPattern.compile("{tag:/N.*/}=subject >/amod/=arc {}=object"));
      } else {
        add(SemgrexPattern.compile("{ner:/PERSON|ORGANIZATION|LOCATION/}=subject >/amod|compound/=arc {ner:/..+/}=object"));
      }
      // { Chris Manning of Stanford }
      if (allowNominalsWithoutNER) {
        add(SemgrexPattern.compile("{tag:/N.*/}=subject >/nmod:.*/=relation {}=object"));
      } else {
        add(SemgrexPattern.compile("{ner:/PERSON|ORGANIZATION|LOCATION/}=subject >/nmod:.*/=relation {ner:/..+/}=object"));
      }
    }});
  }

  /**
   * @see RelationTripleSegmenter#RelationTripleSegmenter(boolean)
   */
  @SuppressWarnings("UnusedDeclaration")
  public RelationTripleSegmenter() {
    this(false);
  }

  /**
   * Extract the nominal patterns from this sentence.
   *
   * @see RelationTripleSegmenter#NOUN_TOKEN_PATTERNS
   * @see RelationTripleSegmenter#NOUN_DEPENDENCY_PATTERNS
   *
   * @param parse The parse tree of the sentence to annotate.
   * @param tokens The tokens of the sentence to annotate.
   * @return A list of {@link RelationTriple}s. Note that these do not have an associated tree with them.
   */
  public List<RelationTriple> extract(SemanticGraph parse, List<CoreLabel> tokens) {
    List<RelationTriple> extractions = new ArrayList<>();
    Set<Triple<Span,String,Span>> alreadyExtracted = new HashSet<>();

    // Run Token Patterns
    for (TokenSequencePattern tokenPattern : NOUN_TOKEN_PATTERNS) {
      TokenSequenceMatcher tokenMatcher = tokenPattern.matcher(tokens);
      while (tokenMatcher.find()) {
        // Create subject
        List<? extends CoreMap> subject = tokenMatcher.groupNodes("$subject");
        Span subjectSpan = Util.extractNER(tokens, Span.fromValues(((CoreLabel) subject.get(0)).index() - 1, ((CoreLabel) subject.get(subject.size() - 1)).index()));
        List<CoreLabel> subjectTokens = new ArrayList<>();
        for (int i : subjectSpan) {
          subjectTokens.add(tokens.get(i));
        }
        // Create object
        List<? extends CoreMap> object = tokenMatcher.groupNodes("$object");
        Span objectSpan = Util.extractNER(tokens, Span.fromValues(((CoreLabel) object.get(0)).index() - 1, ((CoreLabel) object.get(object.size() - 1)).index()));
        if (Span.overlaps(subjectSpan, objectSpan)) {
          continue;
        }
        List<CoreLabel> objectTokens = new ArrayList<>();
        for (int i : objectSpan) {
          objectTokens.add(tokens.get(i));
        }
        // Create relation
        if (subjectTokens.size() > 0 && objectTokens.size() > 0) {
          List<CoreLabel> relationTokens = new ArrayList<>();
          // (add the 'be')
          relationTokens.add(new CoreLabel() {{
            setWord("is");
            setLemma("be");
            setTag("VBZ");
            setNER("O");
            setBeginPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
            setEndPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
            setSentIndex(subjectTokens.get(subjectTokens.size() - 1).sentIndex());
            setIndex(-1);
          }});
          // (add a complement to the 'be')
          List<? extends CoreMap> beofComp = tokenMatcher.groupNodes("$beof_comp");
          if (beofComp != null) {
            // (add the complement
            for (CoreMap token : beofComp) {
              if (token instanceof CoreLabel) {
                relationTokens.add((CoreLabel) token);
              } else {
                relationTokens.add(new CoreLabel(token));
              }
            }
            // (add the 'of')
            relationTokens.add(new CoreLabel() {{
              setWord("of");
              setLemma("of");
              setTag("IN");
              setNER("O");
              setBeginPosition(objectTokens.get(0).beginPosition());
              setEndPosition(objectTokens.get(0).beginPosition());
              setSentIndex(objectTokens.get(0).sentIndex());
              setIndex(-1);
            }});
          }
          // Add extraction
          String relationGloss = StringUtils.join(relationTokens.stream().map(CoreLabel::word), " ");
          if (!alreadyExtracted.contains(Triple.makeTriple(subjectSpan, relationGloss, objectSpan))) {
            extractions.add(new RelationTriple(subjectTokens, relationTokens, objectTokens));
            alreadyExtracted.add(Triple.makeTriple(subjectSpan, relationGloss, objectSpan));
          }
        }
      }

      // Run Semgrex Matches
      for (SemgrexPattern semgrex : NOUN_DEPENDENCY_PATTERNS) {
        SemgrexMatcher matcher = semgrex.matcher(parse);
        while (matcher.find()) {
          // Create subject
          IndexedWord subject = matcher.getNode("subject");
          Span subjectSpan = Util.extractNER(tokens, Span.fromValues(subject.index() - 1, subject.index()));
          List<CoreLabel> subjectTokens = new ArrayList<>();
          for (int i : subjectSpan) {
            subjectTokens.add(tokens.get(i));
          }
          // Create object
          IndexedWord object = matcher.getNode("object");
          Span objectSpan = Util.extractNER(tokens, Span.fromValues(object.index() - 1, object.index()));
          List<CoreLabel> objectTokens = new ArrayList<>();
          for (int i : objectSpan) {
            objectTokens.add(tokens.get(i));
          }
          // Check that the pair is valid
          if (Span.overlaps(subjectSpan, objectSpan)) {
            continue;  // We extracted an identity
          }
          if (subjectSpan.end() == objectSpan.start() - 1 &&
              (tokens.get(subjectSpan.end()).word().matches("[\\.,:;\\('\"]") ||
                  "CC".equals(tokens.get(subjectSpan.end()).tag()))) {
            continue; // We're straddling a clause
          }
          if (objectSpan.end() == subjectSpan.start() - 1 &&
              (tokens.get(objectSpan.end()).word().matches("[\\.,:;\\('\"]") ||
                  "CC".equals(tokens.get(objectSpan.end()).tag()))) {
            continue; // We're straddling a clause
          }
          // Get the relation
          if (subjectTokens.size() > 0 && objectTokens.size() > 0) {
            LinkedList<CoreLabel> relationTokens = new LinkedList<>();
            IndexedWord relNode = matcher.getNode("relation");
            if (relNode != null) {
              // (add the relation)
              relationTokens.add(relNode.backingLabel());
              // (check for aux information)
              String relaux = matcher.getRelnString("relaux");
              if (relaux != null && relaux.startsWith("nmod:") && !"nmod:poss".equals(relaux)) {
                relationTokens.add(new CoreLabel() {{
                  setWord(relaux.substring("nmod:".length()).replace("tmod", "at_time"));
                  setLemma(relaux.substring("nmod:".length()).replace("tmod", "at_time"));
                  setTag("PP");
                  setNER("O");
                  setBeginPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
                  setEndPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
                  setSentIndex(subjectTokens.get(subjectTokens.size() - 1).sentIndex());
                  setIndex(-1);
                }});
              } else if (relaux != null && "nmod:poss".equals(relaux)) {
                relationTokens.addFirst(new CoreLabel() {{
                  setWord("'s");
                  setLemma("'s");
                  setTag("PP");
                  setNER("O");
                  setBeginPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
                  setEndPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
                  setSentIndex(subjectTokens.get(subjectTokens.size() - 1).sentIndex());
                  setIndex(-1);
                }});
                relationTokens.addLast(new CoreLabel() {{
                  setWord("is");
                  setLemma("be");
                  setTag("VBZ");
                  setNER("O");
                  setBeginPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
                  setEndPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
                  setSentIndex(subjectTokens.get(subjectTokens.size() - 1).sentIndex());
                  setIndex(-1);
                }});
              }
            } else {
              // (add the 'be')
              relationTokens.add(new CoreLabel() {{
                setWord("is");
                setLemma("be");
                setTag("VBZ");
                setNER("O");
                setBeginPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
                setEndPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
                setSentIndex(subjectTokens.get(subjectTokens.size() - 1).sentIndex());
                setIndex(-1);
              }});
              // (add an optional prep)
              String rel = matcher.getRelnString("relation");
              String prep = null;
              if (rel != null && rel.startsWith("nmod:") && !"nmod:poss".equals(rel)) {
                prep = rel.substring("nmod:".length()).replace("tmod", "at_time");
              } else if (rel != null && (rel.startsWith("acl:") || rel.startsWith("advcl:")) ) {
                prep = rel.substring(rel.indexOf(":"));
              } else if (rel != null && rel.equals("nmod:poss")) {
                relationTokens.clear();
                prep = "'s";
              }
              if (allowNominalsWithoutNER && "of".equals(prep)) {
                continue;  // prohibit things like "conductor of electricity" -> "conductor; be of; electricity"
              }
              if (prep != null) {
                final String p = prep;
                relationTokens.add(new CoreLabel() {{
                  setWord(p);
                  setLemma(p);
                  setTag("PP");
                  setNER("O");
                  setBeginPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
                  setEndPosition(subjectTokens.get(subjectTokens.size() - 1).endPosition());
                  setSentIndex(subjectTokens.get(subjectTokens.size() - 1).sentIndex());
                  setIndex(-1);
                }});
              }
            }
            // Add extraction
            String relationGloss = StringUtils.join(relationTokens.stream().map(CoreLabel::word), " ");
            if (!alreadyExtracted.contains(Triple.makeTriple(subjectSpan, relationGloss, objectSpan))) {
              extractions.add(new RelationTriple(subjectTokens, relationTokens, objectTokens));
              alreadyExtracted.add(Triple.makeTriple(subjectSpan, relationGloss, objectSpan));
            }
          }
        }
      }
    }

    // Filter downward polarity extractions
    Iterator<RelationTriple> iter = extractions.iterator();
    while (iter.hasNext()) {
      RelationTriple term = iter.next();
      boolean shouldRemove = false;
      for (CoreLabel token : term) {
        if (token.get(NaturalLogicAnnotations.PolarityAnnotation.class) != null &&
            token.get(NaturalLogicAnnotations.PolarityAnnotation.class).isDownwards() ) {
          shouldRemove = true;
        }
      }
      if (shouldRemove) { iter.remove(); }  // Don't extract things in downward polarity contexts.
    }

    // Return
    return extractions;
  }

  /**
   * A counter keeping track of how many times a given pattern has matched. This allows us to learn to iterate
   * over patterns in the optimal order; this is just an efficiency tweak (but an effective one!).
   */
  private final Counter<SemgrexPattern> VERB_PATTERN_HITS = new ClassicCounter<>();

  /** A set of valid arcs denoting a subject entity we are interested in */
  private final Set<String> VALID_SUBJECT_ARCS = Collections.unmodifiableSet(new HashSet<String>(){{
    add("amod"); add("compound"); add("aux"); add("nummod"); add("nmod:poss"); add("nmod:tmod"); add("expl");
    add("nsubj");
  }});

  /** A set of valid arcs denoting an object entity we are interested in */
  private final Set<String> VALID_OBJECT_ARCS = Collections.unmodifiableSet(new HashSet<String>(){{
    add("amod"); add("compound"); add("aux"); add("nummod"); add("nmod"); add("nsubj"); add("nmod:*"); add("nmod:poss");
    add("nmod:tmod"); add("conj:and"); add("advmod"); add("acl");
    // add("advcl"); // Born in Hawaii, Obama is a US citizen; citizen -advcl-> Born.
  }});

  /** A set of valid arcs denoting an adverbial modifier we are interested in */
  public final Set<String> VALID_ADVERB_ARCS = Collections.unmodifiableSet(new HashSet<String>(){{
    add("amod"); add("advmod"); add("conj"); add("conj:and"); add("conj:or"); add("auxpass");
  }});

  private static CoreLabel mockNode(CoreLabel toCopy, int offset, String word, String POS) {
    CoreLabel mock = new CoreLabel(toCopy);
    mock.setWord(word);
    mock.setLemma(word);
    mock.setValue(word);
    mock.setNER("O");
    mock.setTag(POS);
    mock.setIndex(toCopy.index() + offset);
    return mock;
  }

  /**
   * @see RelationTripleSegmenter#getValidSubjectChunk(edu.stanford.nlp.semgraph.SemanticGraph, edu.stanford.nlp.ling.IndexedWord, Optional)
   * @see RelationTripleSegmenter#getValidObjectChunk(edu.stanford.nlp.semgraph.SemanticGraph, edu.stanford.nlp.ling.IndexedWord, Optional)
   * @see RelationTripleSegmenter#getValidAdverbChunk(edu.stanford.nlp.semgraph.SemanticGraph, edu.stanford.nlp.ling.IndexedWord, Optional)
   */
  @SuppressWarnings("StatementWithEmptyBody")
  private Optional<List<CoreLabel>> getValidChunk(SemanticGraph parse, IndexedWord originalRoot,
                                                  Set<String> validArcs, Optional<String> ignoredArc) {
    PriorityQueue<CoreLabel> chunk = new FixedPrioritiesPriorityQueue<>();
    Queue<IndexedWord> fringe = new LinkedList<>();
    IndexedWord root = originalRoot;
    fringe.add(root);

    boolean isCopula = false;
    for (SemanticGraphEdge edge : parse.outgoingEdgeIterable(originalRoot)) {
      String shortName = edge.getRelation().getShortName();
      if (shortName.equals("cop") || shortName.equals("auxpass")) {
        isCopula = true;
      }
    }

    while (!fringe.isEmpty()) {
      root = fringe.poll();
      chunk.add(root.backingLabel(), -root.index());
      for (SemanticGraphEdge edge : parse.incomingEdgeIterable(root)) {
        if (edge.getDependent() != originalRoot) {
          String relStr = edge.getRelation().toString();
          if ((relStr.startsWith("nmod:") &&
               !"nmod:poss".equals(relStr) &&
               !"nmod:npmod".equals(relStr)
              ) ||
              relStr.startsWith("acl:") || relStr.startsWith("advcl:")) {
            chunk.add(mockNode(edge.getGovernor().backingLabel(), 1,
                    edge.getRelation().toString().substring(edge.getRelation().toString().indexOf(":") + 1).replace("tmod","at_time"),
                    "PP"),
                -(((double) edge.getGovernor().index()) + 0.9));
          }
          if (edge.getRelation().getShortName().equals("conj")) {
            chunk.add(mockNode(root.backingLabel(), -1, edge.getRelation().getSpecific(), "CC"), -(((double) root.index()) - 0.9));
          }
        }
      }
      for (SemanticGraphEdge edge : parse.getOutEdgesSorted(root)) {
        String shortName = edge.getRelation().getShortName();
        String name = edge.getRelation().toString();
        //noinspection StatementWithEmptyBody
        if (isCopula && (shortName.equals("cop") || shortName.contains("subj") || shortName.equals("auxpass"))) {
          // noop; ignore nsubj, cop for extractions with copula
        } else if (ignoredArc.isPresent() && ignoredArc.get().equals(name)) {
          // noop; ignore explicitly requested noop arc.
        } else if (!validArcs.contains(edge.getRelation().getShortName().replaceAll(":.*",":*"))) {
          return Optional.empty();
        } else {
          fringe.add(edge.getDependent());
        }
      }
    }

    return Optional.of(chunk.toSortedList());
  }

  /**
   * Get the yield of a given subtree, if it is a valid subject.
   * Otherwise, return {@link java.util.Optional#empty()}}.
   * @param parse The parse tree we are extracting a subtree from.
   * @param root The root of the subtree.
   * @param noopArc An optional edge type to ignore in gathering the chunk.
   * @return If this subtree is a valid entity, we return its yield. Otherwise, we return empty.
   */
  private Optional<List<CoreLabel>> getValidSubjectChunk(SemanticGraph parse, IndexedWord root, Optional<String> noopArc) {
    return getValidChunk(parse, root, VALID_SUBJECT_ARCS, noopArc);
  }

  /**
   * Get the yield of a given subtree, if it is a valid object.
   * Otherwise, return {@link java.util.Optional#empty()}}.
   * @param parse The parse tree we are extracting a subtree from.
   * @param root The root of the subtree.
   * @param noopArc An optional edge type to ignore in gathering the chunk.
   * @return If this subtree is a valid entity, we return its yield. Otherwise, we return empty.
   */
  private Optional<List<CoreLabel>> getValidObjectChunk(SemanticGraph parse, IndexedWord root, Optional<String> noopArc) {
    return getValidChunk(parse, root, VALID_OBJECT_ARCS, noopArc);
  }

  /**
   * Get the yield of a given subtree, if it is a adverb chunk.
   * Otherwise, return {@link java.util.Optional#empty()}}.
   * @param parse The parse tree we are extracting a subtree from.
   * @param root The root of the subtree.
   * @param noopArc An optional edge type to ignore in gathering the chunk.
   * @return If this subtree is a valid adverb, we return its yield. Otherwise, we return empty.
   */
  private Optional<List<CoreLabel>> getValidAdverbChunk(SemanticGraph parse, IndexedWord root, Optional<String> noopArc) {
    return getValidChunk(parse, root, VALID_ADVERB_ARCS, noopArc);
  }

  /**
   * <p>
   * Try to segment this sentence as a relation triple.
   * This sentence must already match one of a few strict patterns for a valid OpenIE extraction.
   * If it does not, then no relation triple is created.
   * That is, this is <b>not</b> a relation extractor; it is just a utility to segment what is already a
   * (subject, relation, object) triple into these three parts.
   * </p>
   *
   * <p>
   *   This method will only run the verb-centric patterns
   * </p>
   *
   * @param parse The sentence to process, as a dependency tree.
   * @param confidence An optional confidence to pass on to the relation triple.
   * @param consumeAll if true, force the entire parse to be consumed by the pattern.
   * @return A relation triple, if this sentence matches one of the patterns of a valid relation triple.
   */
  private Optional<RelationTriple> segmentVerb(SemanticGraph parse, Optional<Double> confidence, boolean consumeAll) {
    // Run pattern loop
    PATTERN_LOOP: for (SemgrexPattern pattern : VERB_PATTERNS) {  // For every candidate pattern...
      SemgrexMatcher m = pattern.matcher(parse);
      if (m.matches()) {  // ... see if it matches the sentence
        if ("nmod:poss".equals(m.getRelnString("prepEdge"))) {
          continue;   // nmod:poss is not a preposition!
        }
        // some JIT on the pattern ordering
        // note[Gabor]: This actually helps quite a bit; 72->86 sentences per second for the entire OpenIE pipeline.
        VERB_PATTERN_HITS.incrementCount(pattern);
        if (((int) VERB_PATTERN_HITS.totalCount()) % 1000 == 0) {
          ArrayList<SemgrexPattern> newPatterns = new ArrayList<>(VERB_PATTERNS);
          Collections.sort(newPatterns, (x, y) ->
                  (int) (VERB_PATTERN_HITS.getCount(y) - VERB_PATTERN_HITS.getCount(x))
          );
          VERB_PATTERNS = newPatterns;
        }
        // Main code
        int numKnownDependents = 2;  // subject and object, at minimum
        // Object
        IndexedWord object = m.getNode("appos");
        if (object == null) {
          object = m.getNode("object");
        }
        assert object != null;
        // Verb
        PriorityQueue<CoreLabel> verbChunk = new FixedPrioritiesPriorityQueue<>();
        IndexedWord verb = m.getNode("verb");
        List<IndexedWord> adverbs = new ArrayList<>();
        Optional<String> subjNoopArc = Optional.empty();
        Optional<String> objNoopArc = Optional.empty();
        if (verb != null) {
          // Case: a standard extraction with a main verb
          IndexedWord relObj = m.getNode("relObj");
          for (SemanticGraphEdge edge : parse.outgoingEdgeIterable(verb)) {
            if ("advmod".equals(edge.getRelation().toString()) || "amod".equals(edge.getRelation().toString())) {
              // Add adverb modifiers
              String tag = edge.getDependent().backingLabel().tag();
              if (tag == null ||
                  (!tag.startsWith("W") && !edge.getDependent().backingLabel().word().equalsIgnoreCase("then"))) {  // prohibit advmods like "where"
                adverbs.add(edge.getDependent());
              }
            } else if (edge.getDependent().equals(relObj)) {
              // Add additional object to the relation
              Optional<List<CoreLabel>> relObjSpan = getValidChunk(parse, relObj, Collections.singleton("compound"), Optional.empty());
              if (!relObjSpan.isPresent()) {
                continue PATTERN_LOOP;
              } else {
                for (CoreLabel token : relObjSpan.get()) {
                  verbChunk.add(token, -token.index());
                }
                numKnownDependents += 1;
              }
            }
          }
          // Special case for possessive with verb
          if ("nmod:poss".equals(m.getRelnString("verb"))) {
            verbChunk.add(mockNode(verb.backingLabel(), -1, "'s", "POS"), ((double) verb.backingLabel().index()) - 0.9);
          }
        } else {
          // Case: an implicit extraction where the 'verb' comes from a relation arc.
          String verbName = m.getRelnString("verb");
          if ("nmod:poss".equals(verbName)) {
            IndexedWord subject = m.getNode("subject");
            verb = new IndexedWord(mockNode(subject.backingLabel(), 1, "'s", "POS"));
            objNoopArc = Optional.of("nmod:poss");
          } else if (verbName != null && verbName.startsWith("nmod:")) {
            verbName = verbName.substring("nmod:".length()).replace("_", " ").replace("tmod", "at_time");
            IndexedWord subject = m.getNode("subject");
            verb = new IndexedWord(mockNode(subject.backingLabel(), 1, verbName, "IN"));
            subjNoopArc = Optional.of("nmod:" + verbName);
          } else {
            throw new IllegalStateException("Pattern matched without a verb!");
          }
        }
        verbChunk.add(verb.backingLabel(), -verb.index());
        // Prepositions
        IndexedWord prep = m.getNode("prep");
        String prepEdge = m.getRelnString("prepEdge");
        if (prep != null) { verbChunk.add(prep.backingLabel(), -prep.index()); numKnownDependents += 1; }
        // Auxilliary "be"
        IndexedWord be = m.getNode("be");
        if (be != null) { verbChunk.add(be.backingLabel(), -be.index()); numKnownDependents += 1; }
        // (adverbs have to be well-formed)
        if (!adverbs.isEmpty()) {
          Set<CoreLabel> adverbialModifiers = new HashSet<>();
          for (IndexedWord adv : adverbs) {
            Optional<List<CoreLabel>> adverbChunk = getValidAdverbChunk(parse, adv, Optional.empty());
            if (adverbChunk.isPresent()) {
              adverbialModifiers.addAll(adverbChunk.get().stream().collect(Collectors.toList()));
            } else {
              continue PATTERN_LOOP;  // Invalid adverbial phrase
            }
            numKnownDependents += 1;
          }
          for (CoreLabel adverbToken : adverbialModifiers) {
            verbChunk.add(adverbToken, -adverbToken.index());
          }
        }
        // (add preposition edge)
        if (prepEdge != null) {
          verbChunk.add(mockNode(verb.backingLabel(), 1,
              prepEdge.substring(prepEdge.indexOf(":") + 1).replace("_", " ").replace("tmod", "at_time"), "PP"), -(verb.index() + 10));
        }
        // (check for additional edges)
        if (consumeAll && parse.outDegree(verb) > numKnownDependents) {
          //noinspection UnnecessaryLabelOnContinueStatement
          continue PATTERN_LOOP;  // Too many outgoing edges; we didn't consume them all.
        }
        List<CoreLabel> relation = verbChunk.toSortedList();

        // Last chance to register ignored edges
        if (!subjNoopArc.isPresent()) {
          subjNoopArc = Optional.ofNullable(m.getRelnString("subjIgnored"));
          if (!subjNoopArc.isPresent()) {
            subjNoopArc = Optional.ofNullable(m.getRelnString("prepEdge"));  // For some strange "there are" cases
          }
        }
        if (!objNoopArc.isPresent()) {
          objNoopArc = Optional.ofNullable(m.getRelnString("objIgnored"));
        }

        // Find the subject
        // By default, this is just the subject node; but, occasionally we want to follow a
        // csubj clause to find the real subject.
        IndexedWord subject = m.getNode("subject");
        /*
        if (parse.outDegree(subject) == 1) {
          SemanticGraphEdge edge =  parse.outgoingEdgeIterator(subject).next();
          if (edge.getRelation().toString().contains("subj")) {
            subject = edge.getDependent();
          }
        }
        */

        // Subject+Object
        Optional<List<CoreLabel>> subjectSpan = getValidSubjectChunk(parse, subject, subjNoopArc);
        Optional<List<CoreLabel>> objectSpan = getValidObjectChunk(parse, object, objNoopArc);
        // Create relation
        if (subjectSpan.isPresent() && objectSpan.isPresent() &&
            CollectionUtils.intersection(new HashSet<>(subjectSpan.get()), new HashSet<>(objectSpan.get())).isEmpty()
            ) {  // ... and has a valid subject+object
          // Success! Found a valid extraction.
          RelationTriple.WithTree extraction = new RelationTriple.WithTree(subjectSpan.get(), relation, objectSpan.get(), parse, confidence.orElse(1.0));
          return Optional.of(extraction);
        }
      }
    }
    // Failed to match any pattern; return failure
    return Optional.empty();
  }

  /**
   * Same as {@link RelationTripleSegmenter#segmentVerb}, but with ACL clauses.
   * This is a bit out of the ordinary, logic-wise, so it sits in its own function.
   */
  private Optional<RelationTriple> segmentACL(SemanticGraph parse, Optional<Double> confidence, boolean consumeAll) {
    IndexedWord subject = parse.getFirstRoot();
    Optional<List<CoreLabel>> subjectSpan = getValidSubjectChunk(parse, subject, Optional.of("acl"));
    if (subjectSpan.isPresent()) {
      // found a valid subject
      for (SemanticGraphEdge edgeFromSubj : parse.outgoingEdgeIterable(subject)) {
        if ("acl".equals(edgeFromSubj.getRelation().toString())) {
          // found a valid relation
          IndexedWord relation = edgeFromSubj.getDependent();
          List<CoreLabel> relationSpan = new ArrayList<>();
          relationSpan.add(relation.backingLabel());
          List<CoreLabel> objectSpan = new ArrayList<>();
          List<CoreLabel> ppSpan = new ArrayList<>();
          Optional<CoreLabel> pp = Optional.empty();

          // Get other arguments
          for (SemanticGraphEdge edgeFromRel : parse.outgoingEdgeIterable(relation)) {
            String rel = edgeFromRel.getRelation().toString();
            // Collect adverbs
            if ("advmod".equals(rel)) {
              Optional<List<CoreLabel>> advSpan = getValidAdverbChunk(parse, edgeFromRel.getDependent(), Optional.empty());
              if (!advSpan.isPresent()) {
                return Optional.empty();  // bad adverb span!
              }
              relationSpan.addAll(advSpan.get());
            }
            // Collect object
            else if (rel.endsWith("obj")) {
              if (!objectSpan.isEmpty()) {
                return Optional.empty();  // duplicate objects!
              }
              Optional<List<CoreLabel>> maybeObjSpan = getValidObjectChunk(parse, edgeFromRel.getDependent(), Optional.empty());
              if (!maybeObjSpan.isPresent()) {
                return Optional.empty();  // bad object span!
              }
              objectSpan.addAll(maybeObjSpan.get());
            }
            // Collect pp
            else if (rel.startsWith("nmod:")) {
              if (!ppSpan.isEmpty()) {
                return Optional.empty();  // duplicate objects!
              }
              Optional<List<CoreLabel>> maybePPSpan = getValidObjectChunk(parse, edgeFromRel.getDependent(), Optional.of("case"));
              if (!maybePPSpan.isPresent()) {
                return Optional.empty();  // bad object span!
              }
              ppSpan.addAll(maybePPSpan.get());
              // Add the actual preposition, if we can find it
              for (SemanticGraphEdge edge : parse.outgoingEdgeIterable(edgeFromRel.getDependent())) {
                if ("case".equals(edge.getRelation().toString())) {
                  pp = Optional.of(edge.getDependent().backingLabel());
                }
              }
              // Add the actual preposition, even if we can't find it.
              if (!pp.isPresent()) {
                pp = Optional.of(new CoreLabel() {{
                  setWord(rel.substring("nmod:".length()).replace("tmod", "at_time"));
                  setLemma(rel.substring("nmod:".length()).replace("tmod", "at_time"));
                  setTag("IN");
                  setNER("O");
                  setBeginPosition(0);
                  setEndPosition(0);
                  setSentIndex(0);
                  setIndex(-1);
                }});
              }
            }
            else if (consumeAll) {
              return Optional.empty();  // bad edge out of the relation
            }
          }

          // Construct a triple
          // (canonicalize the triple to be subject; relation; object, folding in the PP)
          if (!ppSpan.isEmpty() && !objectSpan.isEmpty()) {
            relationSpan.addAll(objectSpan);
            objectSpan = ppSpan;
          } else if (!ppSpan.isEmpty()) {
            objectSpan = ppSpan;
          }
          // (last error checks -- shouldn't ever fire)
          if (!subjectSpan.isPresent() || subjectSpan.get().isEmpty() || relationSpan.isEmpty() || objectSpan.isEmpty()) {
            return Optional.empty();
          }
          // (sort the relation span)
          Collections.sort(relationSpan, (a, b) -> a.index() - b.index());
          // (add in the PP node, if it exists)
          if (pp.isPresent()) {
            relationSpan.add(pp.get());
          }
          // (success!)
          RelationTriple.WithTree extraction = new RelationTriple.WithTree(subjectSpan.get(), relationSpan, objectSpan, parse, confidence.orElse(1.0));
          return Optional.of(extraction);
        }
      }
    }

    // Nothing found; return
    return Optional.empty();
  }

  /**
   * <p>
   * Try to segment this sentence as a relation triple.
   * This sentence must already match one of a few strict patterns for a valid OpenIE extraction.
   * If it does not, then no relation triple is created.
   * That is, this is <b>not</b> a relation extractor; it is just a utility to segment what is already a
   * (subject, relation, object) triple into these three parts.
   * </p>
   *
   * <p>
   *   This method will attempt to use both the verb-centric patterns and the ACL-centric patterns.
   * </p>
   *
   * @param parse The sentence to process, as a dependency tree.
   * @param confidence An optional confidence to pass on to the relation triple.
   * @param consumeAll if true, force the entire parse to be consumed by the pattern.
   * @return A relation triple, if this sentence matches one of the patterns of a valid relation triple.
   */
  public Optional<RelationTriple> segment(SemanticGraph parse, Optional<Double> confidence, boolean consumeAll) {
    // Copy and clean the tree
    parse = new SemanticGraph(parse);
    Util.stripPrepCases(parse);

    // Special case "there is <something>". Arguably this is a job for the clause splitter, but the <something> is
    // sometimes not _really_ its own clause
    IndexedWord root = parse.getFirstRoot();
    if ( (root.lemma() != null && root.lemma().equalsIgnoreCase("be")) ||
         (root.lemma() == null && (root.word().equalsIgnoreCase("is") || root.word().equalsIgnoreCase("are") || root.word().equalsIgnoreCase("were") || root.word().equalsIgnoreCase("be")))) {
      // Check for the "there is" construction
      boolean foundThere = false;
      boolean tooMayArcs = false;  // an indicator for there being too much nonsense hanging off of the root
      Optional<SemanticGraphEdge> newRoot = Optional.empty();
      for (SemanticGraphEdge edge : parse.outgoingEdgeIterable(root)) {
        if (edge.getRelation().toString().equals("expl") && edge.getDependent().word().equalsIgnoreCase("there")) {
          foundThere = true;
        } else if (edge.getRelation().toString().equals("nsubj")) {
          newRoot = Optional.of(edge);
        } else {
          tooMayArcs = true;
        }
      }
      // Split off "there is")
      if (foundThere && newRoot.isPresent() && !tooMayArcs) {
        ClauseSplitterSearchProblem.splitToChildOfEdge(parse, newRoot.get());
      }
    }

    // Run the patterns
    Optional<RelationTriple> verbExtraction = segmentVerb(parse, confidence, consumeAll);
    if (verbExtraction.isPresent()) {
      return verbExtraction;
    } else {
      return segmentACL(parse, confidence, consumeAll);
    }
  }

  /**
   * Segment the given parse tree, forcing all nodes to be consumed.
   * @see RelationTripleSegmenter#segment(edu.stanford.nlp.semgraph.SemanticGraph, Optional)
   */
  public Optional<RelationTriple> segment(SemanticGraph parse, Optional<Double> confidence) {
    return segment(parse, confidence, true);
  }
}
