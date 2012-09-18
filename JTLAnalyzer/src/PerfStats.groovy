/**
 * 
 * This script inspects a JMeter output file (.jtl) to generate aggregated stats. 
 * Aggregates are made by groups VU, assuming the number of VUs is increased in time.
 * Stats are generated in a .csv file in order to facilitate ploting.
 */


 import javax.xml.stream.*

def cl = new CliBuilder(usage: 'groovy PerfStats -f file [-l limit] [-i include] [-s step]')
cl.'?'(longOpt:'help', 'Show usage information and quit')
cl.f(argName:'file', longOpt:'file', args:1, required:false, 'JMeter output file (.jtl) REQUIRED')
cl.i(argName:'label', longOpt:'include', args:1, required:false, 'include only samples whose label contains this label. default : includes everything')
cl.s(argName:'vu step', longOpt:'step', args:1, required:false, 'number VU increments to aggregate on. default  is 100')
cl.l(argName:'milliseconds', longOpt:'limit', args:1, required:false, 'stops gathering stats when MRT is over the given limit (in milliseconds). default : 6000')


def opt = cl.parse(args)

if (!opt) // usage already displayed by cl.parse()
  System.exit(2)
 
if (opt.'?')
{
  cl.usage()
  return
}

// JTL file. For convenience we try to look for a sample.jtl if it's there
def filename = opt.f ? opt.f : "sample.jtl"
def jtlFile = new File(filename)

// stops gathering stats when MRT is over the given limit (in milliseconds)
limit = opt.l ? opt.l as Integer : 8000 

// global var that will tell the parser to strop collecting stats when limit is reached
belowLimit = true

// include only samples whose label contains this label. default : includes everything
label = opt.i ? opt.i : "everything"

// number VU increments to aggregate on. default  is 100
stepSize = opt.s ? opt.s as Integer : 100 


ignoredCount = 0;
threadnames = new TreeSet([]) // names of thread whose stats have been collected

vus = 0 // current number of VU counted
nextStep = stepSize // number of VU for the next increment
collector = new StatCollector() // current collector
allStats = [nextStep : collector] // map of stat collectors by step

// we are displaying a text table, this is the header.
println "Gathering stats for '$label' grouped by increments of $stepSize VU until MRT>=${limit}ms"
def header = ['nb VU','MRT AVG','THG AVG','90% MRT AVG', 'nb sample','RT MAX ','RT MIN','nb error']
header.each {print "${it.padRight(17)}"}
println ""

// start parsing
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

// global stats to be computed
errorRate = 0.0 // % of erros
errorCount = 0; // number of errors
stdev = 0.0 // average standard deviation
sumStd = 0.0 // 
avgRT = 0.0 // average response time
sumRT = 0.0
sumSamples = 0
vu3000 = 0

// generates the csv stats file
def csvFile = new File(filename + ".csv")
csvFile.delete()
csvFile.append header.join(';')
csvFile.append '\n'
allStats.each() { vus, stat ->
	def errors = stat.errors
	def p90mrt = stat.getPercentile90RT()
	def std = stat.getStandardDeviationRT()
	def samples = stat.samples

    def row = [vus, stat.mrt, stat.tps, p90mrt, samples, stat.rtmax, stat.rtmin, errors]
    csvFile.append row.join(';')
    csvFile.append '\n'
    if (p90mrt >= 3000) vu3000 = vus
 	if (p90mrt <= limit) {   
     errorCount+= errors
	 sumRT += p90mrt
	 sumStd += std
	 sumSamples+=samples
 	}
	
};
println "> $csvFile"


// generate the global stats file
avgRT = sumRT/allStats.size()
stdev = sumStd / allStats.size()
def errorRate = ((sumSamples == 0) ? 0 : (errorCount / sumSamples)*100) as Double

def infoFile = new File(filename + ".info.txt")
infoFile.delete()

println "Gathering stats for '$label' grouped by increments of $stepSize VU until MRT>=${limit}ms"
infoFile.append("Requests : $label\n")
infoFile.append("Aggregation : every $stepSize concurrent users\n")
infoFile.append("Limit : ${limit} ms\n")
infoFile.append("Samples: $sumSamples\n")
infoFile.append("Errors: $errorCount\n")
infoFile.append("Error Rate: ${errorRate.round()} %\n")
infoFile.append("Average RT: ${avgRT.round()} ms\n")
infoFile.append("Average RT < 3s: $vu3000 concurrent users\n")
infoFile.append("Standard Deviation RT : ${stdev.round()}\n")
println "\n${infoFile.text}"
println "> $infoFile"



def processStartElement(element) {
	switch(element.name()) {
		
		case 'httpSample':
		  def lb = element.lb
		  def tn = element.tn

		  boolean acceptedLabel = ((label == "everything") || lb.contains(label))
		 
		  if (acceptedLabel) {
		  	threadnames.add(tn) 
		  	vus = threadnames.size() // number of  concurrent users = number of different thread names
	  
	        // if we reached next step, create a new group
		  	if (vus>=nextStep+1) {
		  		collector.print() // print previous collector when we reached the step
		  		nextStep+=stepSize
		  		collector =  new StatCollector()
		  		allStats.put(nextStep, collector)
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
  int vu = 0       // number of VU in the step 
  double mrt = 0      // mean response tume
  double stdv = 0 // standard deviation
  double tps = 0      // throughput average
  int samples = 0  // number of samples 
  def rtmax = 0 // max response time
  def rtmin = 0 // min response time
  def errors = 0 // error
  def label
  def validSamples = 0
  def cumulRt = 0
  List allRT = [] // all Resp. Time
  List percentileRT = [] // 90% best Resp. Time

  
  long startTime = 0
  long endTime = 0

  
  
  	public void visit(def element, def vus) {
  	    def t = element.t.toInteger()  // t is the response time
		def lb = element.lb // lb is the page the label
		def success = element.s // success
		def tn = element.tn
		def ts = element.ts as Long
		
		samples++
	  
		if (success!= null && success == "false")  {
			errors++
			return // we don't count the samples in error
		}
		
		label = tn
		
		allRT.add(t)
		vu = vus 
		
		 validSamples++
		 
		// compute the throughput
		if (startTime == 0) startTime = endTime = ts;
		if (ts < startTime) startTime = ts 
		if (ts > endTime) endTime = ts 
		if (ts > startTime) {
		  def intervalSeconds = (endTime - startTime) / 1000
		  tps = validSamples/intervalSeconds
		} 

		
		cumulRt += t
		mrt = cumulRt / validSamples
		
	
		if (t>rtmax || rtmax==null) {
			rtmax=t
		}
		if (t<rtmin || rtmin==null) {
			rtmin=t
		}

		

	}
	
	

	
	public Double getStandardDeviationRT() {
		def sumdelta=0
		def pRT = getPercentile90RT();
		if (percentileRT.size() <= 0) return 0D;
		
		percentileRT.each() { 
		  def pdelta = (pRT > it) ?  (pRT - it) :  (it - pRT)
		  sumdelta+= pdelta
		}	
		return sumdelta/percentileRT.size()
	}
	
	public Double getPercentile90RT() {
		if (allRT.size <=0 ) { return 0D;}
		percentileRT = allRT.sort()
		def total = percentileRT.size()
		int toCut = (0.1*total)

	   
	    if (total>10) {
	     (1..toCut).each{percentileRT.pop()}
	    } 

	   def sum = 0;
	   percentileRT.each{sum+=it}
	   return sum/percentileRT.size()      
	}
	

	

	
	

	
	public void print() {
		def pad = 17
		def smrt = "${mrt.round()} ms"
		def stps = "${tps.round()} rq/s"
		def smrt90 = "${getPercentile90RT().round()} ms"
		def srtmax = "${rtmax} ms"
		def srtmin = "${rtmin} ms"
		print "${vu.toString().padRight(pad)}"
		print "${smrt.padRight(pad)}"
		print "${stps.padRight(pad)}"		
		print "${smrt90.padRight(pad)}"	
		print "${samples.toString().padRight(pad)}"		
		print "${srtmax.padRight(pad)}"
		print "${srtmin.padRight(pad)}"
		print "${errors.toString().padRight(pad)}"
		println ""
	}	
}


