/**
 * 
 * This script inspect a JMeter output file (.jtl) to calculate the % of pages that give a response below a given limit.
 * It lets you verify goals such as : Page XXX must provide 80% of responses below 1s
 */


 import javax.xml.stream.*

def cl = new CliBuilder(usage: 'groovy ResponseTimeCheck -f file [-l limit] [-r rate] [-i IGNORE]')
cl.h(longOpt:'help', 'Show usage information and quit')
cl.l(argName:'limit', longOpt:'limit', args:1, required:false, 'limit of response time to check in ms')
cl.r(argName:'rate', longOpt:'rate', args:1, required:false, 'Target rate of responses below the limit')
cl.i(argName:'ignore', longOpt:'ignore', args:1, required:false, 'ignored page')
cl.f(argName:'file', longOpt:'file', args:1, required:false, 'JMeter output result file, REQUIRED')
cl.d(argName:'details', longOpt:'details', args:0, required:false, 'display detail per page')

def opt = cl.parse(args)

if (!opt || opt.h) {
	System.exit(1);
}

def filename = opt.f ? opt.f : "sample.jtl"
limit = opt.l ? opt.l.toInteger(): 4000
def jtlFile = new File(filename)
def rateLimit = opt.r ? opt.r.toInteger(): 80
ignore = opt.i ? opt.i : null
ignoredCount = 0;
showDetailsPerPage = opt.d ? true : false



globalStat = new DualCounter("Global");
map = [:] // map of counter per name


// start parsing

println "Goal :  ${rateLimit}% of the responses must be below ${limit/1000}s "
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




def result =  (globalStat.roundedRate > rateLimit) ? "OK" : "KO"

println "Result : $result (${globalStat.roundedRate}%)"

if (ignoredCount>0) println "  - $ignoredCount responses skipped ('$ignore')"
  
  if (showDetailsPerPage) {
    println "---- split per page ----"

    // print all the results
    for ( e in map ) {
	    def eresult =  (e.value.roundedRate > rateLimit) ? "OK" : "KO"
	    e.value.print()
	    println " ($eresult)"
    }
    println "----"
  }





def processStartElement(element) {
	switch(element.name()) {
		
		case 'httpSample':
		  def lb = element.lb
		  if (lb != ignore) 
		    globalStat.visit(element,limit)
		  else
		    ignoredCount++
			
		  def pageCounter = (map[lb]) ? map[lb] : new DualCounter(lb)
		  pageCounter.visit(element,limit)
		  map[lb] = pageCounter
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


class DualCounter {
	int count;
	int countBelow;
	def label;

	public DualCounter(String label) {
		this.label = label;
	}

	public void visit(def element, def limit) {
		def t = element.t.toInteger()  // t is the response time
		def lb = element.lb // lb is the page the label
		count++
		// count number of hit below the limit
		if (t<limit)  countBelow++

	}

	public def getRoundedRate() {
		def rateBelow = (countBelow/count)*100
		def roundedRate = Math.round(rateBelow * 100) / 100 // round at 2 decimals
		return roundedRate;
	}
	
	public void print() {
		def roundedRate = getRoundedRate()
		print "$label : ${roundedRate}% "
	}
}