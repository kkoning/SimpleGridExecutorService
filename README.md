# SimpleGridExecutorService

This is a relatively straightforward implementation of a Java ExecutorService that lets you take advantage of distributed computation resources without using a much heavier package like [Apache Ignite](https://ignite.incubator.apache.org/).  If you don't need all the features related to distributed in-memory computation being integrated with persistant storage.  Transactions, Hibernate, cache management, Hadoop accelleration, etc.... this package does *none* of that.  It's just a non-nonsense way to run a large number of computationally expensive `Runnable`s or `Callables` using a cluster rather than a single JVM.  There are probably easier ways to do this, but I got frustrated searching and decided just to write a simple one myself.  It was written to help execute a large number of agent-based models in an evolutionary computation system, so it probably makes design choices I wasn't even aware of with that task in mind.  So that's a small NIH warning I guess.  Anyway...

## Installation and Use

This is meant to be used as a library to include in a Java project that wants to use Java's built-in `ExecutorService` API, but to have the actual execution happen remotely.  I built this where the evolutionary computation system would run on a central node, but then farm out the fitness evaluation for groups of agents to remote hosts.  

On the central node, you'll want to instantiate a `sres.GridExecutorService`, where you can specify (1) the port to listen on, (2) a shared authentication secret, and (3) optional use of local CPU resources.  On the worker nodes, you'll want to start up a thread with the `main()` function in `RemoteEvaluationWorker`.  The command line requires you to specify the network address of the central node and the shared secret.  

 As long as the tasks submitted to `GridExecutorService` are `Serializable`, they may be executed on one of the remotes. The results (if any) are made available to the originator.  In the extremely likely event you want to do something with the results of the remote computation, you should use or extend the `RemoteCallable` class.  Its constructor will allow you to specify a closure to process the result of the underlying `Callable`.

There's a few test classes that are useful for demonstration purposes in addition to testing, but to save you some time...

### Understanding RemoteCallable

```
// Create the service.  This version does not accept remote connections
GridExecutorService ges = new GridExecutorService();
	
// A trivial RemoteCallable.  Typically there would be a computationally intensive
// callable rather than a trivial closure returning "Hello World"
RemoteCallable rc = new RemoteCallable(() -> "Hello World", (s) -> System.out.println(s));

// Submit the task to be executed
ges.submit(rc);

// shut down and await termination
ges.shutdown();
ges.awaitTermination(1, TimeUnit.MINUTES);
```

### Submitting other types of tasks

If you submit anything other than a `RemoteCallable`, then `GridExecutorService` will check whether or not the `Runnable` or `Callable` submitted is serializable.  If so, it will proxy the object and attempt to execute it, perhaps remotely if those computational resources are available.  If the submitted task is not serializable, it will submit it to run only in the local ThreadPoolRunner queue.

The `submit(...)` varieties of the `ExecutorService` API do return `Future` objects consistent with the API documentation.  (The proxies implement the `FutreRunnable` interface, so they are used directly)  However, this isn't somehing I've really tested because it's not something I plan to use.  Or at least, I don't plan to use it _yet_.

### Deployment

Currently, these classes assume that Serialization of the submitted objects is possible on both sides of any remote connections.  This means that any classes that are submitted this way must be available/deployed on the remote worker classes as well.  For my use case, this will require Agent-Based Model code to be deployed in both places.  My understanding is that this is something that could be change by writing a custom classloader, but that's not something that was important to me to implement right away.


## Development Notes

This is a fairly light distribution layer over Java's built-in execution services.  For that reason, you might need to look into the code itself, but AFAICT it's also fairly simple as far as things like this go.  The requester side proxy is `RemoteEvaluationTracker` and the worker side proxy is `RemoteEvaluationWorker`.  There's also a minor extension of `ThreadPoolExecutor` called `LocalGridExecutor` that performs some of the extra features.

### Tests

There are some simple tests in TestRemoteCallable that demonstrate how that works.  `demo()` contains the code above, plus a slightly longer task that demonstrates the need to await termination rather than just letting the main thread exit.  It also includes a `localTest()` that demonstrates the heart of how the result proxying works from the networking, serialization, and task queue management.  They're designed to run by themselves to verify things are working, but two of them can also be used with an external compute source to demonstrate that things work with remote hosts as well.

### Future Development

Right now, this requires that all the necessary closses to do the computation are pre-loaded on the remote workers.  That's not a huge hardship, but it might be annoying in environments with both fast iteration and where you don't have deployment synchronization from some other source.  (i.e., Dropbox syncing an executable .jar or something)  I know that other more advanced packages do class loading, but this isn't something that I wanted to put the time in for right away.  Maybe later...
