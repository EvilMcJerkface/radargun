---
---

Design documentation
--------------------

### Main and Workers

RadarGun has two types of nodes:

**Main**

There is always only one main. This loads the configuration, controls execution of the benchmark and produces reports. The main is started as a server, workers connect to it. No Service (tested system - see below) is started on the main.

**Worker**

Workers are nodes which execute the benchmark, gather statistics and other info. The workers start a Service - this is the tested system (such as Infinispan, EHCache, Memcached client etc.), there can be many workers.

>Nothing prevents execution of multiple workers on one machine. This is convenient for benchmark development, but usually undesirable for benchmarking. Exceptions may apply, e.g. in a case when the service is single-threaded and you want to host multiple services on multi-core machine to fully utilize it.

### Stages

This is the core concept of RadarGun since its first days. After all Workers connect to the Main, Main starts the scenario: a sequence of steps called Stages. Main synchronizes execution of these so that in every moment each Worker executes the same stage, in parallel.

There are two types of stages: MainStage (executed only on Main), and DistStage (distributed to the workers). 

MainStage has only two methods: init() and execute(), dividing the initialization of class and actual execution of the stage.

In DistStage, after the stage is initialized by initOnMain() or initOnWorker(), the workers run executeOnWorker(). The return value is either DistStageAck - simple acknowledgement that stage ran successfully or failed - or any serializable object extending it, usually carrying payload such as test statistics. Main collects these from all workers and then runs processAcksOnMain() on the main node.

The stages have Properties configurable from the benchmark configuration file. The Properties are evaluated on the target node (Main or Worker) and the value can differ on each node (this is useful for example if it contains some file-system path). RadarGun can automatically convert basic types such as primitives, or collections of primitives, you can define simple converters from strings or complex converters from XML (note: this is very new feature).

The XSD schema for the configuration XML is generated in each build of RadarGun by scanning the properties on stages, therefore, the documentation can be always up to date with sources. Also a Wiki documentation generator is available for presentation here, in the Markdown syntax.

### Plugins, Service and Traits

RadarGun can benchmark many distributed systems (such as Infinispan or Oracle Coherence). Each such system needs an adaptor, these adaptors are built as maven modules in the plugins/ directory - for historical reasons, we call them simply Plugins. Therefore, Plugin is module used to adapt any distributed system used in RadarGun. We sometimes also use the term Plugin for the distributed system itself.

In order to interface the vendor-specific API with RadarGun, we need an adaptation layer. As there are usually multiple features that each Plugin provides (such as lifecycle, information about cluster, transactions, java.util.Map-like interface etc.), RadarGun defines interfaces called Traits (annotated with @Trait, see org.radargun.traits package).

RadarGun retrieves the Traits from a Service - this is a class (annotated with @Service) in the Plugin which binds the Trait implementation (as functionality) to the instance of the distributed system. The Service is created before each scenario and persists until the scenario ends. It can have several no-arg methods annotated with @ProvidesTrait - these are called after the service is created and the runtime objects returned are analyzed: this way RadarGun detects which Traits the Service provides.{::comment} Checkout [the list of provided Traits]({{page.path_to_root}}architecture/trait_list.html).{:/comment}

Each plugin may provide multiple Services (in order to spare resources). For example, plugin infinispan60 provides service with Infinispan in embedded mode, service with Infinispan HotRod client, and service that can run standalone Infinispan Server. The plugin and service is selected in configuration using: 

{% highlight xml %}
    <setup plugin="my_plugin" service="my_service" />
{% endhighlight %}

The service name here is only symbolic; each plugin contains the file `conf/plugin.properties` which binds these names to the implementing class:

    service.my_service=com.my.Service

Subsequent executions of a benchmark scenario with different configuration is achieved by restarting the worker process, setting classpath anew. This guarantees that there won't be any leaked threads or memory leaks from the previous run, code is JITed again, and allows to change JVM arguments. Workers are restarted when the cluster size is changing, too.

>Previous versions of RadarGun used classloader isolation and were aggresively shutting down leaked threads, but this was found too unreliable and the practice was discontinued.

### Statistics and Reporters

The statistics (values measured and events detected) obtained throughout the benchmark processing are stored in-memory on the Main node until all configurations (cluster sizes x configurations) are executed. Then these are passed to each of the configured reporters in order to create the desired output.

Methods on Traits that should be benchmarked are called Operations. The Trait provides a constants `Operation` (uniquely identified with a name in form TraitName.MethodName) passed to the statistics to identify these methods. 

> In some scenarios, we need to denote a special version of this operation (to be distinguished in the statistics) - Operation.derive() method can be used to create a suffixed version of this operation.

Usually each thread on worker operates on private instance of statistics, the statistics from multiple threads and subsequently workers can be merged. The list of statistics from stage form a Test - in one test, the results of one Operation should be approximately same, provided that parameters of the stage execution ar the same. The results of a Test from different service configurations can be later compared against each other in the reporter.
If the results are expected to change during the Test execution (e.g. because we increase the number of testing threads, or the amount of data stored in the service grows), we can split the test into Iterations - this is more convenient and allows better presentation than using distinct Tests (chack `repeat` stage).

> Many tests provide an option to append test results to previous iteration of the same stage, check `amend-test` parameter

The Reporters are pluggable and implemented in a similar fashion as the plugins: each reporter module contains file plugin.properties with the reporter classes it provides. Current implemenations can create HTML report, CSV files convenient for further processing or upload the results into a [PerfRepo](https://github.com/perfcake/perfrepo) database.

Statistics implementation and the stages where these statistics are loosely coupled - Stage only needs to report the duration of each operation (either successful or nor) to statistics. On the other hand, Reporter needs to present the results in a way that fits the collected data, and therefore, it is quite tightly coupled with the concrete Statistics - e.g. if you want to draw a histogram in the report, you have to collect more than a sum of durations of requests and request count.

RadarGun user is responsible for using appropriate configuration of statistics and reporter plugins, but the Reporter needs a generic way of retrieving data from the Statistics instance. That's why we use the Representation abstraction: the Reporter can ask the statistics for instance of particular class (e.g. DefaultOutcome - simple struct containing number of requests, errors, mean and maximum time - or Histogram) and the statistics either return the instance with requested data or null if this Representation is not supported for these Statistics.

### Extensions

RadarGun is extensible in plugins, reporters and also in stages. We try to keep only the most common Traits and Stages in core (e.g. those managing service lifecycle) while Traits specific to cache-like API, querying and the tests using these are in their respective modules in the extensions/ directory. You can start adapting your own API, and there's a Hello-World example of a module defining an extension; try

    mvn clean install -Pexample-extension

You can also build your JAR separately and copy it into the `lib/` directory afterwards - all JARs in this directory are scanned for the stages, although the XSD will not reflect it.
