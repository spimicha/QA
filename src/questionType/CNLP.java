package questionType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.stanford.nlp.ling.CoreAnnotations.PartOfSpeechAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.trees.HeadFinder;
import edu.stanford.nlp.trees.SemanticHeadFinder;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreeCoreAnnotations.TreeAnnotation;
import edu.stanford.nlp.util.CoreMap;

public class CNLP {
	
	
	public static void main(String[] args) throws IOException{

		String fileLocation = new String("C:\\Users\\spimi\\workspace\\Thesis\\Data\\QuestionClassification\\TREC_10.label.txt");//
		new CNLP(fileLocation);
		
	}
	
	static Tree nPhrase = null;
	
	public CNLP(String file) throws IOException{
		

		
		// creates a StanfordCoreNLP object, with POS tagging, lemmatization,
		// NER, parsing, and coreference resolution
		List<String> trainingData = loadData(file);
		List<String[]> featureList = new ArrayList<String[]>();
		// "tokenize, ssplit, pos, lemma, ner, parse, dcoref");
		Properties props = new Properties();
		props.setProperty("ssplit.isOneSentence", "true");
		props.setProperty("annotators", "tokenize, ssplit, parse");
		
		
		StanfordCoreNLP pipeline = new StanfordCoreNLP(props);
		SemanticHeadFinder headFinder = new SemanticHeadFinder();
		List<String> questionList;
		List<String> posList;
		for(int i=0; i<trainingData.size(); i++){
			nPhrase = null;
			questionList = new ArrayList<String>();
			posList = new ArrayList<String>();
			// read some text in the text variable
			String questionText = trainingData.get(i);
			//questionText.replaceAll("'", "`");
			//String questionText = "Who is Duke Ellington ?";
			//String questionText = "What is the proper name of a female walrus ?";
			//String questionText = "What is the sales tax in Minessoata ?";
			//String questionText = "What year did the Titanic sink ?";
			//String questionText = "What is an atom ?";
			// create an empty Annotation just with the given text
			//String questionText = "What is a group of turkeys called ?";
			Annotation document = new Annotation(questionText);
			// run all Annotators on this text
			pipeline.annotate(document);
			// these are all the sentences in this document // a CoreMap is essentially a Map that uses class objects as keys and // has values with custom types
			CoreMap sentence = document.get(SentencesAnnotation.class).get(0);
			
			// this is the parse tree of the current sentence
			Tree questionParseTree = sentence.get(TreeAnnotation.class);
			//head finder object	 
			for (CoreLabel token: sentence.get(TokensAnnotation.class)) {
				 // this is adding the text of the token to questionList
		        questionList.add(token.get(TextAnnotation.class));
		        // this is adding the POS tag of the token to the posList
		        posList.add(token.get(PartOfSpeechAnnotation.class));
		 
			}

			String whWord = getWhWord(questionList.get(0));
			String[] wordAndLabel = extractHeadWord(questionList, posList, questionText, questionParseTree, headFinder);
			System.out.println(i+" "+whWord + " " + wordAndLabel[0] + " " + wordAndLabel[1]);
			String[] arr = {whWord, wordAndLabel[0], wordAndLabel[1]};
			featureList.add(arr);
			//System.exit(0);
		}
		
		writeData(featureList);
	}
		
	

	/**
	 * Algorithm to extract head word. Uses method QTypeRegex 
	 * @param question
	 * @param headWord
	 * @param label
	 * @param sentence
	 * @return String of extracted question head
	 */
	public static String[] extractHeadWord(List<String> questionList, List<String> posList, String question, Tree tree, SemanticHeadFinder headFinder) {
		if(questionList.get(0).equals("When") || questionList.get(0).equals("Where") || questionList.get(0).equals("Why")){
			String[] returnArray = {null, null};
			return returnArray;
		}
		if(questionList.get(0).equals("How")){
			String[] returnArray = {questionList.get(1), posList.get(1)};
			return returnArray;
		}
		if(questionList.get(0).equals("What")){
			String condition = checkRegex(question);
			if(condition != "other" && !condition.equals("HUM:desc")){
				String[] returnArray = {condition, null};
				return returnArray;
			}
			
		}

		if(questionList.get(0).equals("Who") && checkRegex(question).equals("HUM:desc")){
			String[] returnArray = {"HUM:desc", null};
			return returnArray;
		}
		int candidateIndex = getCandidate(tree, headFinder, questionList);
		String candidateWord = "";
		String candidateLabel = "";
		if(candidateIndex > -1){
			candidateWord = questionList.get(candidateIndex);
			candidateLabel = posList.get(candidateIndex);
		}
		if(candidateLabel.startsWith("NN")){
			String[] returnArray = {candidateWord, candidateLabel};
			return returnArray;
		}
		for(int i=0; i<posList.size()-1; i++){		
			 if(posList.get(i).startsWith("NN")){
				 String[] returnArray = {questionList.get(i), posList.get(i)};
				 return returnArray;
			 }
		}
		String[] returnArray = {null, null};
		return returnArray;
	}
	
	
	/**
	 * Takes a String question checks against all the regular expression patterns.
	 * If pattern is found, condition name is added to List
	 * @param question
	 * @return ArrayList of condition names (Strings)
	 */
	public static String checkRegex(String question){
		String condition = "";
		List<String> returnList = new ArrayList<String>();
		String descDef1 = "[Ww]hat (is|are)( (a|an|the))?( \\w+)( \\w+)?.?.?";
		String descDef2 = "[Ww]hat (do|does) .+ mean.?.?";
		String entySubs = "[Ww]hat (is|are) .+ ((composed of)|(made out of)).?.?";
		String descDesc = "[Ww]hat does .+ do.?.?";
		String entyTerm = "[Ww]hat do you call .+";
		String descReas1 = "[Ww]hat (causes|cause).*";
		String descReas2 = "[Ww]hat (is|are) .+ used for.?.?";
		String abbrExp = "[Ww]hat (does|do) .+ stand for.?.?";
		String humDesc = "[Ww]ho (is|was) [A-Z]\\w+ .*";
		String[] conditionName = { "DESC:def", "DESC:def", "ENTY:sub",
				"DESC:desc", "ENTY:term", "DESC:reason", "DESC:reason",
				"ABBR:exp", "HUM:desc" };
		String[] conditions = { descDef1, descDef2, entySubs, descDesc,
				entyTerm, descReas1, descReas2, abbrExp, humDesc };

		for (int i = 0; i < conditions.length; i++) {
			if (question.matches(conditions[i])) {
				condition = conditionName[i];
				return condition;
			}
		}
		return "other";

	}

	
	/**
	 * Takes as input Coremap sentence, and returns and array of type
	 * string with the headString and then the label
	 * @param sentence
	 * @return
	 */
	public static int getCandidate (Tree tree, SemanticHeadFinder headFinder, List<String> questionList){
		Tree backupTree = tree.deepCopy();
		Tree head = getSemanticHead(tree.firstChild(), tree , headFinder);
		String wordIndexString = head.getChild(0).label().toString();
		int headWordIndex = Integer.parseInt(wordIndexString.substring(wordIndexString.lastIndexOf("-")+1, wordIndexString.length()))-1;
		
		String candidateWord = questionList.get(headWordIndex);
		if(candidateWord.equals("name" )|| candidateWord.equals("type") || candidateWord.equals("kind") || candidateWord.equals("genre") || candidateWord.equals("group")){
			headWordIndex = addPostFix (backupTree, headFinder)-1;
		}
		return headWordIndex;
	}
		
	
	/**
	 * Get semantic head of current tree. Bottom up procedure.
	 * @param node
	 * @param parent
	 * @param headFinder
	 * @return semantic head or null
	 */
	public static Tree getSemanticHead(Tree node, Tree parent, SemanticHeadFinder headFinder) {
		int index = -1000;
		for (Tree child : node.children()) {
			if (parent.value().equals("NP") && nPhrase == null) {//Set NP in case of no NN being found
				nPhrase = node;
			}
			getSemanticHead(child, node, headFinder);
		}
		if (!node.isLeaf() && !node.isPreTerminal()) {
			Tree head = headFinder.determineHead(node);
			index = parent.objectIndexOf(node);	
			parent.removeChild(index);
			parent.addChild(index, head);
		}
		if (parent.label().toString().equals("ROOT")) {// parent.label().toString()=="ROOT"
			return parent.getChild(index);
		}
		else{
			return null;
		}
	}
	
	
	/**

	/**
	 * If any head word of type name, type, kind, genre, group, PostFix fixes
	 * the head word to the other noun-phrase in the question. Uses BFS traversal
	 * @param node
	 * @param parent
	 * @param phraseList
	 * @param headFinder
	 * @param headWord
	 * @return List of Pre-terminals (NN) suitable
	 */
	public static int addPostFix(Tree rootNode, SemanticHeadFinder headFinder) {
		int wordIndex = -1;
		List<Tree> visited = new ArrayList<Tree>();
	    Queue<Tree> queue = new LinkedList<Tree>();
	    queue.add(rootNode);
	    visited.add(rootNode);
	    while(!queue.isEmpty()){
	    	Tree node = (Tree) queue.remove();
	    	for(Tree child:node.children()){
	    		if(!visited.contains(child)){
	    			visited.add(child);
	    			if(child.value().equals("PP")){
	    				List<Tree> grandChildren = child.getChildrenAsList();
	    				if(grandChildren.size()>1 && grandChildren.get(0).value().equals("IN") && grandChildren.get(1).value().equals("NP")){
	    					Tree childCopy = child.deepCopy();
	    					childCopy.setValue("ROOT");
	    					Tree head = getSemanticHead(childCopy.getChildrenAsList().get(1), childCopy, headFinder);
	    					String wordIndexString = head.getChild(0).label().toString();
	    					wordIndex = Integer.parseInt(wordIndexString.substring(wordIndexString.lastIndexOf("-")+1, wordIndexString.length()));
	    				}
	    			}
	    			queue.add(child);
	    		}
	    	}
	    }    
		return wordIndex;
	}


	
	/**
	 * PostFix helper
	 * @param node
	 * @param headWord
	 * @return false if one of leaves is equal to the head word
	 */
	public static boolean CheckLeaves(Tree node, String headWord) {
		List<Tree> leaves = node.getLeaves();
		for (Tree leaf : leaves) {
			if (leaf.toString().equals(headWord)) {
				return false;
			}
		}
		return true;
	}
	
	
	
	
	
	/**
	 * Feature 1: wh-word
	 * @param question
	 * @return string wh-word or rest
	 */
	public String getWhWord(String questionWord){
		if(questionWord.equals("What"))
			return "what";
		if(questionWord.equals("Which"))
			return "which";
		if(questionWord.equals("When"))
			return "when";
		if(questionWord.equals("Where"))
			return "where";
		if(questionWord.equals("Who"))
			return "who";
		if(questionWord.equals("How"))
			return "how";
		if(questionWord.equals("Why"))
			return "why";
		else
			return "rest";
	}
	
	
	
	/**
	 * Load training Data
	 * @param fileLocation
	 * @return List of strings with category description names
	 * @throws IOException
	 */
	public List<String> loadData(String fileLocation) throws IOException{
		List<String> lines = new ArrayList<String>();
		File file = new File(fileLocation);
		BufferedReader br = new BufferedReader(new FileReader(file));
		String line = br.readLine();
		while (line != null){
			String[] newLine = line.split("\\s", 2);
		    lines.add(newLine[1]);
		    line = br.readLine();
		}
		return lines;
	}
	
	
	public void writeData(List<String[]> featureList) throws FileNotFoundException, UnsupportedEncodingException{
		PrintWriter writer = new PrintWriter("C:\\Users\\spimi\\workspace\\Thesis\\src\\questionType\\features.txt", "UTF-8");
		for(int i=0; i<featureList.size(); i++){	
			writer.println(featureList.get(i)[0]+"\t"+featureList.get(i)[1]+"\t"+featureList.get(i)[2]);	
		}
		writer.close();
	}

	public static String[] toList(String question){
		return question.split("\\s");
	}

}
