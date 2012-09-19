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
cl.i(argName:'label', longOpt:'include', args:1, required:false, 'include only samples whose label contains this label. default : includes all')
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

// include only samples whose label contains this label. default : includes all
filter = opt.i ? opt.i : null

// number VU increments to aggregate on. default  is 100
stepSize = opt.s ? opt.s as Integer : 100 


// names of thread whose stats have been collected
threadnames = new TreeSet([]) 

// current number of VU counted
vus = 0

// number of VU for the next increment
nextStep = stepSize 

// current collector
//collector = new StatCollector() 

// map of stat collectors by step
allStats = [ : ] 

// total number of samples
counter = 0




print "aggregating stats by groups of $stepSize threads"
if (filter) print " for samples that match '$filter'"



// start parsing
use (StaxParser) { processFile(jtlFile) }


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

// print the last collected group
//collector.fprint(vus + collector.validSamples + collector.errors, PAD)



// global stats to be computed
errorRate = 0.0 // % of errors
errorCount = 0; // number of errors
stdev = 0.0 // average standard deviation
sumStd = 0.0 // 
avgRT = 0.0 // average response time
sumRT = 0.0
sumSamples = 0
vu3000 = 0
vulimit = 0

def header = ['Concurrent Users','Mean RT (ms)','Throughput (rq/s)','90% RT (ms)', 'samples','MAX RT ','MIN RT','errors', 'duration (s)', 'Total Samples']

// generates the csv stats file
def csvFile = new File(filename + ".csv")
csvFile.delete()
csvFile.append header.join(';')
csvFile.append '\n'

// we are displaying a text table, this is the header.
//println "Gathering stats for '$label' grouped by increments of $stepSize VU until MRT>=${limit}ms"
PAD = 17
println ""
header.each {print "${it.padRight(PAD)}"}
println ""
allStats.each() { vus, stat ->
	stat.fprint(vus as Integer,PAD)
	def errors = stat.errors
	def p90mrt = stat.getPercentile90RT()
	def std = stat.getStandardDeviationRT()
	def samples = stat.validSamples

    def row = [vus, stat.mrt, stat.tps, p90mrt, samples, stat.rtmax, stat.rtmin, errors, stat.duration, stat.samples]
    csvFile.append row.join(';')
    csvFile.append '\n'
    if (p90mrt <= 3000) vu3000 = vus
 	if (p90mrt <= limit) {   	 
	 vulimit = vus
     errorCount+= errors
	 sumRT += p90mrt // we compute average of 90 percentile MRT
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
String sfilter = filter ? "requests='$filter'," : ""
infoFile.append("Options:$sfilter limit=$limit, step=$stepSize\n")
infoFile.append("Samples: $sumSamples\n")
infoFile.append("Errors: $errorCount\n")
infoFile.append("Error Rate: ${errorRate.round(2)} %\n")
infoFile.append("Global Mean Response Time (MRT): ${avgRT.round()} ms\n")
infoFile.append("Standard Deviation MRT : ${stdev.round()} ms\n")
infoFile.append("Max Concurrent Users with MRT < 3s: $vu3000\n")
infoFile.append("Max Concurrent Users with MRT < ${limit/1000}s: $vulimit\n")

println "\n${infoFile.text}"
println "> $infoFile"



def processStartElement(element) {
	switch(element.name()) {
		
		case 'httpSample':
		  def lb = element.lb
		  def tn = element.tn
		  def activethreads = element.na as Long
		  
		  collector = getCollector(activethreads)

		  /*
		  threadnames.add(tn)
		  vus = threadnames.size() // number of  concurrent users = number of different thread names
		  
		  if (vus>=nextStep) {    // if we reached next step, create a new aggregate
			  collector.fprint(vus, PAD)
			  nextStep+=stepSize	  
			  collector =  new StatCollector()
			  allStats.put(nextStep, collector)
		  }
		  */
		  
		 collector.visit(element, filter)
		 counter++;


		break

	}
}


/*
 * Get the collector for a given number of concurrent threads
 */
def getCollector(long activethreads) {
	if (activethreads == 0) activethreads = 1
	
	def remainder = (activethreads % stepSize)
	
	def floor = (activethreads - remainder) + stepSize
	//if (floor == 0) floor = stepSize
	//if (remainder > 1) println "floor $activethreads > $floor "

	def collector = allStats[floor]
	if (collector == null) {
		collector = new StatCollector()
		allStats[floor] = collector
		print "."
	} 
	return collector
}

class StaxParser { 
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
  //int vu = 0       // number of VU in the step 
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
  long duration // duration of the 
  
  long startTime = 0L
  long endTime = 0

  
  
  	public void visit(def element, def filter) {
  	    def t = element.t.toInteger()  // t is the response time
		def lb = element.lb // lb is the page the label
		def success = element.s // success
		def tn = element.tn
		def ts = element.ts as Long
		
		samples++
		
		
		// compute the throughput
		if (startTime == 0) startTime = endTime = element.ts as Long
		if (ts < startTime) startTime = ts // might happen if first sample is not the first in time
		if (ts > endTime) endTime = ts
		
		// recalculate throughput since the start of this collector
		if (ts > startTime) {
		  duration = (endTime - startTime) / 1000
		 if (duration>0) tps = samples/duration // throughput is computed on all samples
		}
	  
		// skip errors
		if (success!= null && success == "false")  {
			errors++
			return // we don't count the samples in error
		}
		
		// ignore ampty and filtered requests
		label = tn
		boolean matchFilter = (filter != null && lb.contains(filter))
		
		boolean shouldIgnore = ((label.trim().length() == 0) || matchFilter)
		if (shouldIgnore) {
			return ; // filter 
		}
		
			
		validSamples++
		
		// store the response time, we'll need it for stats
		allRT.add(t)
		 
		
		// mean RT
		cumulRt += t
		mrt = cumulRt / validSamples
		
		// min and max RT
		if (t>rtmax || rtmax==null) rtmax=t
		
		if (t<rtmin || rtmin==null)  rtmin=t
		

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
	

	

	
	

	
	public void fprint(int info, int pad) {
	
		def smrt = "${mrt.round()} ms"
		def stps = "${tps.round()} rq/s"
		def smrt90 = "${getPercentile90RT().round()} ms"
		def srtmax = "${rtmax} ms"
		def srtmin = "${rtmin} ms"
		def svu = "${info} "
		def sduration = "${duration} ms"
		def ssamples = "${samples}"
		print "${svu.padRight(pad)}"
		print "${smrt.padRight(pad)}"
		print "${stps.padRight(pad)}"		
		print "${smrt90.padRight(pad)}"	
		print "${validSamples.toString().padRight(pad)}"		
		print "${srtmax.padRight(pad)}"
		print "${srtmin.padRight(pad)}"
		print "${errors.toString().padRight(pad)}"
		print "${sduration.padRight(pad)}"
		print "${ssamples.padRight(pad)}"
		println ""
	}	
}


