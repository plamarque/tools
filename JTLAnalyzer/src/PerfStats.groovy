/**
 * 
 * This script inspects a JMeter output file (.jtl) to generate aggregated stats. 
 * Agregates are made by VU. The result file contains : 
 * Number of Virtual Users (VU), Mean Response Time (MRT), 90% Line Mean Response Time (90%MRT), 'Max. Response Time (RTMax),'Min Response Time' (RTMin), Throughput (THG) , 90% Line Throughput (90%THG), Number of Samples (Samples) and Number of errors (errors).
 * Stats are generatd in a .csv file in order to facilitate ploting.
 */


 import javax.xml.stream.*

def cl = new CliBuilder(usage: 'groovy ResponseTimeCheck -f file [-l label]')
cl.h(longOpt:'help', 'Show usage information and quit')
cl.f(argName:'file', longOpt:'file', args:1, required:false, 'JMeter output result file, REQUIRED')
cl.i(argName:'include', longOpt:'include', args:1, required:false, 'include only samples whose label contains this string')
cl.s(argName:'step', longOpt:'step', args:1, required:false, 'step of VU to aggregate on')
cl.l(argName:'limit', longOpt:'limit', args:1, required:false, 'stops gathering stats when MRT is over the given limit (in milliseconds)')


def opt = cl.parse(args)

if (!opt || opt.h) {
	System.exit(1);
}

def filename = opt.f ? opt.f : "sample.jtl"
def jtlFile = new File(filename)


limit = opt.l ? opt.l as Integer : 6000 // maximum RT above which we stop gathering stats. default = 6s
belowLimit = true

ignoredCount = 0;
showDetailsPerPage = opt.d ? true : false
label = opt.i ? opt.i : "everything"


samples = 0;
vuset = new TreeSet([])
stepSize = opt.s ? opt.s as Integer : 100 // steps of VU increment used for aggregating
vus = 0
nextStep = stepSize
allStats = [new StatCollector()];

// start parsing
println "Stats for '$label' (limit=$limit)"
def header = ['nb VU','MRT AVG','THG AVG','90% MRT AVG','90% THG AVG', 'nb sample','RT MAX ','RT MIN','nb error']
header.each {print "${it.padRight(17)}"}
println ""

use (StaxCategory) { processFile(jtlFile) }


def processFile(jtlFile) {
	def reader
	try {

		reader = XMLInputFactory.newInstance().createXMLStreamReader(new FileReader(jtlFile));
		
		while (reader.hasNext()) {
			if (reader.startElement)
				processStartElement(reader)
			reader.next()

		}
	} finally {
		reader?.close()
	}
}


errorRate = 0.0 // % of erros
errorCount = 0; // number of errors
stdev = 0.0 // average standard deviation
sumStd = 0.0 // 
avgRT = 0.0 // average response time
sumRT = 0.0

def csvFile = new File(filename + ".csv")
csvFile.delete()
csvFile.append header.join(';')
csvFile.append '\n'
allStats.each {
	def errors = it.errors;
	errorCount+= errors;
	def rt = it.getPercentile90RT();
	def std = it.getStandardDeviationRT();
	['nb VU','MRT AVG','THG AVG','90% MRT AVG','90% THG AVG','nb sample','RT MAX ','RT MIN','nb error']
    def row = [it.vu, it.mrt, it.tps, rt, it.getPercentile90TPS(), it.samples, it.rtmax, it.rtmin, errors]
    csvFile.append row.join(';')
    csvFile.append '\n'
	sumRT += rt
	sumStd += std
}
println "> $csvFile"


avgRT = sumRT/allStats.size()
stdev = sumStd / allStats.size()
errorRate = (errorCount / samples)*100

def infoFile = new File(filename + ".info.txt")
infoFile.delete()
infoFile.append("Samples: $samples\n")
infoFile.append("Errors: $errorCount\n")
infoFile.append("Error Rate: $errorRate %\n")
infoFile.append("Average Response Time: $avgRT ms\n")
infoFile.append("Standard RT Deviation: $stdev\n")
println "\n${infoFile.text}"
println "> $infoFile"



def processStartElement(element) {
	switch(element.name()) {
		
		case 'httpSample':
		  def lb = element.lb
		  def tn = element.tn
		  def collector = allStats.last();


		  boolean acceptedLabel = ((label == "everything") || lb.contains(label))
		 
		  if (acceptedLabel && belowLimit) {
		  	samples++
		  	vuset.add(tn) 
		  	vus = vuset.size()
	  
		  	if (vus>=nextStep+1) {
		  		collector.print()
		  		if (collector.mrt >= limit) {
		  			belowLimit = false
		  			break;
		  		}
		  		nextStep+=stepSize
		  		collector =  new StatCollector()
		  		allStats.add(collector)
		  		collector.visit(element,vus)  		
		  	} else {		  	
			  collector.visit(element,vus)	
		  	}

		  } 
		  else
		    ignoredCount++

		break

	}
}

class StaxCategory { 
	static Object get(XMLStreamReader self, String key) {
		return self.getAttributeValue(null, key)
	}
	static String name(XMLStreamReader self) {
		return self.name.toString()
	}
	static String text(XMLStreamReader self) {
		return self.elementText
	}
}


/** **/
class StatCollector {
  int vu;        // number of VU in the step 
  def mrt;      // mean response tume
  def stdv; // standard deviation
  def tps;      // throughput average
  def samples = 0; // number of samples 
  def rtmax;  // max response time
  def rtmin;  // min response time
  def errors=0; // error
  def label;
  def count = 0
  def cumulRt = 0;
  def allRT = [] // all Resp. Time
  def percentileRT = [] // 90% best Resp. Time
  def allTPS = [] // all throughputs
  def percentileTPS = [] // 90% throughputs
  
  long startTime = 0;
  long endTime = 0;
  
  
  	public void visit(def element, def vus) {
  	    def t = element.t.toInteger()  // t is the response time
		def lb = element.lb // lb is the page the label
		def success = element.s // success
		def tn = element.tn
		def ts = element.ts as Long
		

		if (success!= null && success == "false")  {
			errors++
			return // we don't count the samples in error
		}
		
		label = tn
		count++
		allRT.add(t)
		
		vu = vus 
		samples++
		
		// get the time range
		if (startTime == 0L) startTime = ts 
		else endTime = ts
		
		// compute the throughput
		if (endTime > 0L) {
			def interval = (endTime - startTime)
			def intervalSeconds = interval / 1000
			if (intervalSeconds == 0) intervalSeconds = 1
		    tps = samples/intervalSeconds
		    allTPS.add(tps)
		}
		
		
		cumulRt += t
		mrt = cumulRt / samples
		
	
		if (t>rtmax || rtmax==null) {
			rtmax=t
		}
		if (t<rtmin || rtmin==null) {
			rtmin=t
		}

		

	}
	
	public def getStandardDeviationRT() {
		def sumdelta=0
		def pRT = getPercentile90RT();
		percentileRT.each() { 
		  def pdelta = (pRT > it) ?  (pRT - it) :  (it - pRT)
		  sumdelta+= pdelta
		}	
		return sumdelta/percentileRT.size()
	}
	
	public def getPercentile90RT() {
		percentileRT = allRT.sort()
		def total = percentileRT.size()
		int toCut = (0.1*total)
		def start = (total-toCut)
	   
	    if (total>10) {
	     (1..toCut).each{percentileRT.pop()}
	    } 

	   def sum = 0;
	   percentileRT.each{sum+=it}
	   return sum/percentileRT.size()      
	}
	
    public def getPercentile90TPS() {
		percentileTPS = allTPS.sort()
		def total = percentileTPS.size()
		int toCut = (0.1*total)
		def start = (total-toCut)
	   
	    if (total>10) {
	     (1..toCut).each{percentileTPS.pop()}
	    } 

	   def sum = 0;
	   percentileTPS.each{sum+=it}
	   return sum/percentileTPS.size()    
    }
	

	
	

	
	public void print() {
		def pad = 17
		print "${vu.toString().padRight(pad)}"
		print "${mrt.toString().padRight(pad)}"
		print "${tps.toString().padRight(pad)}"		
		print "${getPercentile90RT().toString().padRight(pad)}"
		print "${getPercentile90TPS().toString().padRight(pad)}"		
		print "${samples.toString().padRight(pad)}"		
		print "${rtmax.toString().padRight(pad)}"
		print "${rtmin.toString().padRight(pad)}"
		print "${errors.toString().padRight(pad)}"
		println ""
	}	
}


