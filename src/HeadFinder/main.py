import nltk
from nltk.corpus import wordnet as wn
import re, sys, os
from nltk.stem import WordNetLemmatizer


def GetWordShape(theString):
    """Return whether the first letter of each word begins with capital,
    lower, mixed, digits or other
    """
    isUpperCase = False
    isLowerCase = False
    isDigit = False
    theString = GetUnigram(theString)
    for i in theString:
        if i[0].isalnum():
            if i[0].isalpha:
                if i[0].isupper():
                    isUpperCase = True
                if i[0].islower():
                    isLowerCase = True
            if i[0].isdigit():
                isDigit = True
    if isUpperCase==True and isLowerCase==True and isDigit==False:
        return 'mixed'
    if isUpperCase==True and isLowerCase==False and isDigit==False:
        return 'upper_case'
    if isUpperCase==False and isLowerCase==True and isDigit==False:
        return 'lower_case'
    if isUpperCase==False and isLowerCase==False and isDigit==True:
        return 'all_digits'
    else:
        return 'other'
        

def PreProcess(theString):
    """Returns string in form of list, where each item is either a word or punctuation"""
    theString = re.sub(r"([\w/'+$\s-]+|[^\w/'+$\s-]+)\s*", r"\1 ", theString).strip()
    return re.split("\s", theString)


def GetUnigram(theString):
    """Returns list of Unigrams"""
    theString
    return theString.split(' ')


def GetBigram(theString):
    """Returns list of Bigrams"""
    unigrams = GetUnigram(theString)
    returnList = []
    for i in range(len(unigrams)-1):
        returnList.append(unigrams[i]+' '+unigrams[i+1])
    return returnList


def GetTrigram(theString):
    """Returns list of Trigrams"""
    unigrams = GetUnigram(theString)
    returnList = []
    for i in range(len(unigrams)-2):
        returnList.append(unigrams[i]+' '+unigrams[i+1]+' '+unigrams[i+2])
    return returnList


def getCategories(fileLocation):
    """Returns the categories (coarse & fine grained)"""
    subCatList = []
    catList = []
    with open(fileLocation) as f:
        data = f.read().split('\n')
    for i in data:
        theTuple = i.split('\t')
        theTuple[0] = theTuple[0].strip()
        if theTuple[0]!='ABBR' and theTuple[0]!='ENTY' and theTuple[0]!='DESC' and theTuple[0]!='HUM' and theTuple[0]!='LOC' and theTuple[0]!='NUM':
            subCatList.append(theTuple)
        else:
            catList.append(theTuple)
    return catList, subCatList


def mostSimilarCategory(optimumSense, categories):
    """Returns most similar category based on sense given
    using path similarity"""
    categoryScores = []
    theSynsets = []
    for i in range(len(categories)):
        score = 0.0
        theSynset = None
        for j in categories[i][1].split(' '):
            currentScore = 0.0
            currentCatSynset = wn.synsets(j)
            if currentCatSynset:
                for k in currentCatSynset:
                    currentScore = optimumSense.path_similarity(k)
                    if currentScore > score:
                        score = currentScore
                        theSynset = k
                        #print score, theSynset, optimumSense
        theSynsets.append(theSynset)
        categoryScores.append(score)
    maxVal = max(categoryScores)
    returnList = []
    for i in range(len(categoryScores)):
        if categoryScores[i] == maxVal:
            returnList.append(categories[i])
    return returnList



def finalDecision(MostSimilarList, optimumSense):
    """If more than one category with (equal) max path similarity score, this returns a final 
    decision by taking into summing all path similarities words in description (where one exists) 
    and dividing by the number of words used"""
    categoryScores = []
    theSynsets = []
    for i in range(len(MostSimilarList)):
        score = 0.0
        theSynset = None
        numWords = 0
        for j in MostSimilarList[i][1].split(' '):#for each word in definition
            currentScore = 0.0
            currentCatSynset = wn.synsets(j)
            if currentCatSynset:
                numWords += 1
                kScore = 0.0
                for k in currentCatSynset:#for each synset of current word def
                    kScore = optimumSense.path_similarity(k)
                    if kScore > currentScore:
                        currentScore = kScore
            score += currentScore
        score = score / numWords
    return MostSimilarList[i], score, numWords
                    
                


def Algo2(question, headWord, label):
    """computes the maximum number of common words between gloss of this sense and gloss of any sense 
    of the context words. Among all head word senses, the sense which results in the maximum common 
    words is chosen as the optimal sense and is returned"""
    synsets = wn.synsets(headWord, pos=label)
    if not synsets:
        synsets = wn.synsets(headWord)
    if synsets:
        h = [synsets[i].definition() for i in range(len(synsets))]
        q = question
        count, index = 0, 0
        maxCount = -1
        optimum = None
        for s in h:#for each sense s for h do
            count = 0
            for w in q.split(' '):#for each context word w in q do  
                y = wn.synsets(w)
                wSenses = [y[i].definition() for i in range(len(y))]#get senses of w
                sumMax = 0
                for wSense in wSenses:#for each sense        
                    for word in wSense:#and each word of each sense
                        if word in s.split(' '):#if common word found
                            sumMax += 1
                count = count + sumMax
            if count > maxCount:
                maxCount = count
                optimum = s
                index = h.index(s)
        return synsets[index]
    return None


def getHypernym(depth, sense):
    """Returns the hypernym string of the sense at the required depth"""
    theName = [wn.synset(sense.name())]
    for i in range(depth):
        newName = wn.synset(theName[0].name()).hypernyms()
        if newName:
            theName = newName
            continue
        else:
            break
    return theName[0]


def getFeatureLists(featuresLoc):
    """Loads file and returns list of features already computed """
    with open(featuresLoc) as f:
        lines = f.read().strip()
        lines = lines.split('\n')
    features = []
    for line in lines:
        features.append(line.split('\t'))
    return features


def loadQuestions(file):
    """Loads and returns list of questions"""
    returnList = []
    with open(file) as f:
        lines = f.read().strip()
        lines = lines.split('\n')
    for line in lines:
        line = ''.join([i if ord(i) < 128 else ' ' for i in line])
        returnList.append(re.split("\w+:\w+", line, maxsplit=1)[1])  
    return returnList


def convertPOS(thelabel):
    if(thelabel.startswith('J')):
        return  wn.ADJ
    if(thelabel.startswith('N')):
        return  wn.NOUN
    if(thelabel.startswith('V')):
        return  wn.VERB
    if(thelabel.startswith('R')):
        return  wn.ADV
    

                

def main(questionLoc, categoriesLoc, featuresLoc):
    categories, subCategories = [],[]
    categories, subCategories = getCategories(categoriesLoc)
    questions = loadQuestions(questionLoc) 
    features = getFeatureLists(featuresLoc)
    finalString = ''

    for i in range(len(questions)):
        disambedSens = None
        whWord = features[i][0]
        headWord = features[i][1]
        label = features[i][2]
        label = convertPOS(label)
        question = questions[i].strip()
        uni = GetUnigram(question)
        bi = GetBigram(question)
        tri = GetTrigram(question)
        wordShape = GetWordShape(question)
        #print i, whWord, headWord, label, questions[i]
        if ':' not in headWord:
            optimalSense = Algo2(questions[i], headWord, label)
            if optimalSense is not None:
                disambedSens = getHypernym(5, optimalSense)
                cat = mostSimilarCategory(disambedSens, subCategories)
                print question, cat, len(cat)
                if len(cat)>1:
                    cat = finalDecision(cat, disambedSens)
                print cat
        #print whWord, disambedSens, wordShape, uni, bi, tri
        

questionLoc = "C:\\Users\\spimi\\workspace\\Thesis\\Data\\QuestionClassification\\train_5500.label.txt"   
categoriesLoc = "C:\\Users\\spimi\\workspace\\Thesis\\Data\\QuestionClassification\\categories.txt"
featuresLoc = "C:\\Users\\spimi\\workspace\\Thesis\\src\\features.txt"


main(questionLoc, categoriesLoc, featuresLoc)
