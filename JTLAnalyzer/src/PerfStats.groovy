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
cl.i(argName:'includes', longOpt:'includes', args:1, required:false, 'include only samples whose label contains this label. default : includes all')
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
includes = opt.i ? opt.i : null

// number VU increments to aggregate on. default  is 100
stepSize = opt.s ? opt.s as Integer : 100 


// names of thread whose stats have been collected
threadnames = new TreeSet([]) 

// current number of VU counted
currentvus = 0

// number of VU for the next increment
nextStep = stepSize 

// current collector
//collector = new StatCollector() 

// map of stat collectors by step
allStats = [ : ] 

// total number of samples
counter = 0




print "aggregating stats by groups of $stepSize threads"
if (includes) print " for samples that match '$includes'"



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
totalSamples = 0;


def header = ['Concurrent Users','Mean RT (ms)','Throughput (rq/s)','90% RT (ms)', 'Retained Samples','MAX RT ','MIN RT','Errors', 'Step Duration (s)', 'Total Samples']

// generates the csv stats file
def csvFile = new File(filename + ".csv")
csvFile.delete()
csvFile.append header.join(';')
csvFile.append '\n'

// we are displaying a text table, this is the header.
PAD = 17
println ""
header.each {print "${it.padRight(PAD)}"}
println ""
allStats.each() { vus, stat ->
	stat.fprint(vus as Integer,PAD)
	def errors = stat.errors
	def p90mrt = stat.getPercentile90RT()
	def std = stat.getStandardDeviationRT()
	def success = stat.success
	totalSamples+=stat.samples
    def row = [vus, stat.mrt, stat.tps, p90mrt, success, stat.rtmax, stat.rtmin, errors, stat.duration, stat.samples]
    csvFile.append row.join(';')
    csvFile.append '\n'
    if (p90mrt <= 3000) vu3000 = vus
 	if (p90mrt <= limit) {   	 
	 vulimit = vus
     errorCount+= errors
	 sumRT += p90mrt // we compute average of 90 percentile MRT
	 sumStd += std
	 sumSamples+=success
 	}
	
};
println "> $csvFile"


// generate the global stats file
avgRT = sumRT/allStats.size()
stdev = sumStd / allStats.size()
def errorRate = ((sumSamples == 0) ? 0 : (errorCount / sumSamples)*100) as Double
def retainedRate = (totalSamples ==0) ? 0 : (sumSamples*100/totalSamples) as Double
def infoFile = new File(filename + ".info.txt")
infoFile.delete()
String sincludes = includes ? ", includes='$includes'" : ""
infoFile.append("RUN INFO: MAX VU=${currentvus}, STEP=${stepSize} VU, Stats limit=${limit/1000} seconds$sincludes\n")
infoFile.append("METRICS: \n")
infoFile.append("  Max Users < ${limit/1000}s : $vulimit \n")
infoFile.append("  Max Users < 3s : $vu3000 \n")
infoFile.append("  Overall MRT : ${avgRT.round()} ms\n")
infoFile.append("  Standard Deviation MRT : ${stdev.round()} ms\n")
infoFile.append("  Retained Samples: $sumSamples (${retainedRate.round(2)} %)\n")
infoFile.append("  Errors: $errorCount (${errorRate.round(2)} %)\n")
println "\n${infoFile.text}"
println "> $infoFile"



def processStartElement(element) {
	switch(element.name()) {
		
		case 'httpSample':
		  def activethreads = element.na as Long	
		  if (activethreads < currentvus)	  {
			  //println "ignored sample for $activethreads VUs (current : $currentvus) : ${element.lb} has"
			  break;
		   }
		  currentvus = activethreads
		  collector = getCollector(activethreads)
		  collector.visit(element, includes)
		  counter++;
		  break

	}
}



/*
 * Get the collector for a given number of concurrent threads
 */
def getCollector(long activethreads) {


	
	// 0-100 > 100, 101-200 > 200
	def remainder = ((activethreads-1) % stepSize)	
	def floor = (activethreads-1 - remainder) + stepSize
	
	def collector = allStats[floor]
	if (collector == null) {
		collector = new StatCollector()
		allStats[floor] = collector
		print "-> $floor"
	}
	//println "vu: ${activethreads} duration: ${collector.duration} samples: ${collector.samples} tps : ${collector.tps}"
	
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
  def label
  def errors = 0 // number of errors
  def success = 0 // number of success
  def cumulRt = 0
  List allRT = [] // all Resp. Time
  List percentileRT = [] // 90% best Resp. Time
  long duration // duration of the step
  
  long startTime = 0L
  long endTime = 0

  
  
  	public void visit(def element, def includes) {
  	    def t = element.t.toInteger()  // t is the response time
		def lb = element.lb // lb is the page the label
		def isSuccess = element.s // success
		def tn = element.tn
		def ts = element.ts as Long
		
		samples++
		
		
		// compute the throughput
		if (startTime == 0) startTime = endTime = element.ts as Long
		if (ts < startTime) startTime = ts // might happen if first sample is not the first in time
		if (ts > endTime) endTime = ts
		
		duration = (endTime - startTime) / 1000 // duration of this step in seconds
		if (duration>0) tps = samples/duration	 // throughput is computed on all samples
   
	

		// ignore empty and not included requests
		
		boolean matchIncludes = includes ? lb.contains(includes) : true
		boolean shouldIgnore = ((tn.trim().length() == 0) || !matchIncludes)
		
		// println " $lb / label: $tn / success: $isSuccess / includes: '$includes' / matchIncludes: '$matchIncludes' / shouldIgnore: $shouldIgnore"
		
		if (shouldIgnore) {
			return ; 
		}
		
		// skip errors
		if (isSuccess!= null && isSuccess == "false")  {
			
			errors++
			return // we don't count the samples in error
		}
		
	
		success++
		
		// store the response time, we'll need it for stats
		allRT.add(t)
		 
		
		// mean RT
		cumulRt += t
		mrt = cumulRt / success
		
		// min and max RT
		if (t>rtmax || rtmax==0) rtmax=t
		
		if (t<rtmin || rtmin==0)  rtmin=t
		

	}
	
	
public Double getThroughput() {

	  def duration = (endTime - startTime) / 1000 // duration in seconds
	 if (duration>0) tps = samples/duration	 // throughput is computed on all samples

  //println "duration: $duration samples: $samples tps : $tps"
  return tps

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
		def sduration = "${duration} s"
		def ssamples = "${samples}"
		print "${svu.padRight(pad)}"
		print "${smrt.padRight(pad)}"
		print "${stps.padRight(pad)}"		
		print "${smrt90.padRight(pad)}"	
		print "${success.toString().padRight(pad)}"		
		print "${srtmax.padRight(pad)}"
		print "${srtmin.padRight(pad)}"
		print "${errors.toString().padRight(pad)}"
		print "${sduration.padRight(pad)}"
		print "${ssamples.padRight(pad)}"
		println ""
	}	
}


