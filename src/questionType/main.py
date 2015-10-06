import nltk
from nltk.corpus import wordnet as wn
import re, sys, os
from nltk.stem import WordNetLemmatizer
from nltk.corpus import stopwords
stop = stopwords.words('english')
from pywsd.similarity import *
from nltk import wsd
wordnet_lemmatizer = WordNetLemmatizer()


def GetWordShape(theList):
    """Return whether each word is all capital,
    all lower, mixed, all digits or other
    """

    returnList = []
    for word in theList:
        lower_case = False
        upper_case = False
        mixed_case = False
        other_case = False
        if word.isdigit():
            returnList.append('all_digit')
        else:
            for letter in word:
                if letter.isalpha:
                    if letter.islower():
                        lower_case = True
                    if letter.isupper():
                        upper_case = True
                else:
                    other_case = True
            if other_case == True:
                returnList.append('other_case')
            elif upper_case == True and lower_case == True:
                returnList.append('mixed_case')
            elif upper_case == True:
                returnList.append('upper_case')
            else:
                returnList.append('lower_case') 
    return returnList
            
    

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
            wordList = theTuple[1].split(' ')
            subCatList.append({theTuple[0]:wordList})
        else:
            wordList = theTuple[1].split(' ')
            catList.append({theTuple[0]:wordList})
    return catList, subCatList


def getCommonWords(string1, string2):
    regex = re.compile('[^a-zA-Z ]')    
    commonWordsNum = 0
    string1 = regex.sub('', string1).split(' ')
    string2 = regex.sub('', string2).split(' ')
    for i in string1:
        if i not in stop:
            if i in string2: 
                commonWordsNum += 1
    return commonWordsNum


def getHypernym(question, depth, theSynset):
    """Returns the hypernym string of the sense at the required depth"""
    hyper = lambda s: s.hypernyms()
    synsetList = list(theSynset.closure(hyper))
    if synsetList:
        if len(synsetList) > 5:
            return synsetList[5]
        else:
            return synsetList[len(synsetList)-1]
    else:
        return theSynset
    

    
    

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




def mostSimilarCategory(theSense, theCategories):
    maxValue = 0
    catName = ''
    posList = None
    for cat in theCategories:
        for key, values in cat.iteritems():
            posList = nltk.pos_tag(values)
            index = 0
            contextSentence = ' '.join(values)
            for word in values:#this is each word in the category     
                catSense = wsd.lesk(contextSentence, word)
                if catSense:
                    similarityValue = wn.path_similarity(catSense, theSense)
                    if similarityValue!=None:
                        if  similarityValue > maxValue:
                            maxValue = similarityValue
                            catName = key
                index += 1
    return catName


         
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
        if headWord == 'null':
            headWord = None
        label = features[i][2]
        label = convertPOS(label)
        question = questions[i].strip()
        uni = GetUnigram(question)
        bi = GetBigram(question)
        tri = GetTrigram(question)
        wordShape = GetWordShape(uni)
        if headWord != None and ':' not in headWord:
            disambigSense = wsd.lesk(question, headWord, pos=label)
            if disambigSense:
                directHypernym = getHypernym(question, 5, disambigSense)
                indirectHypernym = mostSimilarCategory(disambigSense, subCategories)
        print whWord, headWord#, disambigSense, wordShape, uni, bi, tri



questionLoc = "C:\\Users\\spimi\\workspace\\Thesis\\Data\\QuestionClassification\\TREC_10.label.txt"   
categoriesLoc = "C:\\Users\\spimi\\workspace\\Thesis\\Data\\QuestionClassification\\categories.txt"
featuresLoc = "C:\\Users\\spimi\\workspace\\Thesis\\src\\questionType\\features.txt"


main(questionLoc, categoriesLoc, featuresLoc)
