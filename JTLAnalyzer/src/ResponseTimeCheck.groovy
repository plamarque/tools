/**
 * This script inspect a JMeter output file (.jtl) to calculate the % of pages that give a response below a given limit.
 * It lets you verify goals such as : Page XXX must provide 80% of responses below 1s
 */


def cl = new CliBuilder(usage: 'groovy ResponseTimeCheck -f file [-l limit] [-r rate] [-i IGNORE]')
cl.h(longOpt:'help', 'Show usage information and quit')
cl.l(argName:'limit', longOpt:'limit', args:1, required:true, 'limit of response time to check in ms')
cl.r(argName:'rate', longOpt:'rate', args:1, required:false, 'Target rate of responses below the limit')
cl.i(argName:'ignore', longOpt:'ignore', args:1, required:false, 'ignored page')
cl.f(argName:'file', longOpt:'file', args:1, required:true, 'JMeter output result file, REQUIRED')


def opt = cl.parse(args)

if (!opt || opt.h) {
	System.exit(1);
}

def filename = opt.f ? opt.f : "sample.jtl"
def limit = opt.l ? opt.l.toInteger(): 4000
def jtlFile = new File(filename)
def rateLimit = opt.r ? opt.r.toInteger(): 80
def ignore = opt.i ? opt.i: null
def ignoredCount = 0;


// start parsing
def testResult = new XmlParser().parseText(jtlFile.text)
println "scanning ${jtlFile}..."

DualCounter globalStat = new DualCounter("Global");
def map = [:] // map of counter per name

testResult.httpSample.each() {
	def lb = it.@lb // it's the label
	
	if (lb != ignore) 
	  globalStat.visit(it,limit)
	else
	  ignoredCount++;
	  
	def pageCounter = (map[lb]) ? map[lb] : new DualCounter(lb)
	pageCounter.visit(it,limit);
	map[lb] = pageCounter;
}


def result =  (globalStat.roundedRate > rateLimit) ? "OK" : "KO"

println "Global Result : $result"
println "${globalStat.roundedRate}% of the responses are < ${limit}ms (goal was ${rateLimit}%)"
if (ignoredCount>0)
  println "  note: $ignoredCount responses were ignored from global stats ('$ignore')"
println "---- split per page ----"

// print all the results
for ( e in map ) {
	def eresult =  (e.value.roundedRate > rateLimit) ? "OK" : "KO"
	e.value.print()
	println " ($eresult)"
}
println "----"




class DualCounter {
	int count;
	int countBelow;
	def label;

	public DualCounter(String label) {
		this.label = label;
	}

	public void visit(Node n, def limit) {
		def t = n.@t.toInteger()  // t is the response time
		def lb = n.@lb // lb is the page the label
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