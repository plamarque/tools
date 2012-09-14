/**
 * 
 * This script inspect a JMeter output file (.jtl) to calculate the % of pages that give a response below a given limit.
 * It lets you verify goals such as : Page XXX must provide 80% of responses below 1s
 */


 import javax.xml.stream.*

def cl = new CliBuilder(usage: 'groovy ResponseTimeCheck -f file [-l label]')
cl.h(longOpt:'help', 'Show usage information and quit')
cl.f(argName:'file', longOpt:'file', args:1, required:false, 'JMeter output result file, REQUIRED')
cl.l(argName:'label', longOpt:'label', args:1, required:false, 'filter ONLY on this sample label')

def opt = cl.parse(args)

if (!opt || opt.h) {
	System.exit(1);
}

def filename = opt.f ? opt.f : "sample.jtl"
def jtlFile = new File(filename)
def csvFile = new File(filename + ".csv")

ignoredCount = 0;
showDetailsPerPage = opt.d ? true : false
label = opt.l ? opt.l : "everything"


samples = 0;
vuset = new TreeSet([])
stepSize = 100 // steps of VU increment used for aggregating
vus = 0
nextStep = stepSize
allStats = [new StatCollector()];

// start parsing

println "Will generate stats for $label"
println "analyzing ${jtlFile}...."
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


println "max VU: $vus"
println "$samples samples processed ($ignoredCount filtered)"
println "aggregated in ${allStats.size()} by steps of $stepSize"



csvFile.delete()
def header = ['VU','MRT','TPS','90%MRT','90%TPS','Samples','RTMAX','RTMIN','errors']
csvFile.append header.join(';')
csvFile.append '\n'
allStats.each {
    def row = [it.vu, it.mrt, it.tps, it.getPercentile90RT(), it.getPercentile90TPS(), it.samples, it.rtmax, it.rtmin, it.errors]
    csvFile.append row.join(';')
    csvFile.append '\n'
}

println "generated $csvFile"





def processStartElement(element) {
	switch(element.name()) {
		
		case 'httpSample':
		  def lb = element.lb
		  def tn = element.tn
		  
		  if ((label == "everything") || lb.contains(label)) {
		  	samples++
		  	vuset.add(tn) 
		  	vus = vuset.size()
		  	
		  	def collector = allStats.last();
		  
		  	collector.visit(element,vus)
		  
		  	if (vus>=nextStep) {
		  		collector.print()
		  		nextStep+=stepSize
		  		collector =  new StatCollector()
		  		allStats.add(collector)
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
			//println "new max found on $tn: $rtMax"
		}
		if (t<rtmin || rtmin==null) {
			rtmin=t
			//println "new min found on $tn: $rtMin"
		}

		

	}
	
	public def getStandardDeviation() {
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
		println "$vu;$mrt;${getStandardDeviation()};$tps;${getPercentile90RT()};${getPercentile90TPS()};$samples;$rtmax;$rtmin;$errors"
	}	
}


