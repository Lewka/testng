package org.testng;

import static org.testng.internal.Utils.isStringBlank;

import com.google.inject.Injector;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import org.testng.collections.Lists;
import org.testng.collections.Maps;
import org.testng.collections.Sets;
import org.testng.internal.*;
import org.testng.internal.annotations.IAnnotationFinder;
import org.testng.internal.invokers.ConfigMethodArguments;
import org.testng.internal.invokers.ConfigMethodArguments.Builder;
import org.testng.internal.invokers.IInvocationStatus;
import org.testng.internal.invokers.IInvoker;
import org.testng.internal.invokers.InvokedMethod;
import org.testng.internal.objects.ObjectFactoryImpl;
import org.testng.internal.thread.ThreadUtil;
import org.testng.reporters.JUnitXMLReporter;
import org.testng.reporters.TestHTMLReporter;
import org.testng.reporters.TextReporter;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

/**
 * <CODE>SuiteRunner</CODE> is responsible for running all the tests included in one suite. The test
 * start is triggered by {@link #run()} method.
 */
public class SuiteRunner implements ISuite, ISuiteRunnerListener {

  private static final String DEFAULT_OUTPUT_DIR = "test-output";

  private final Map<String, ISuiteResult> suiteResults = Maps.newLinkedHashMap();
  private final List<TestRunner> testRunners = Lists.newArrayList();
  private final Map<Class<? extends ISuiteListener>, ISuiteListener> listeners =
      Maps.newLinkedHashMap();

  private String outputDir;
  private final XmlSuite xmlSuite;
  private Injector parentInjector;

  private final List<ITestListener> testListeners = Lists.newArrayList();
  private final Map<Class<? extends IClassListener>, IClassListener> classListeners =
      Maps.newLinkedHashMap();
  private final ITestRunnerFactory tmpRunnerFactory;
  private final DataProviderHolder holder;

  private boolean useDefaultListeners = true;

  // The remote host where this suite was run, or null if run locally
  private String remoteHost;

  // The configuration
  // Note: adjust test.multiplelisteners.SimpleReporter#generateReport test if renaming the field
  private final IConfiguration configuration;

  private ITestObjectFactory objectFactory;
  private Boolean skipFailedInvocationCounts = Boolean.FALSE;
  private final List<IReporter> reporters = Lists.newArrayList();

  private final Map<Class<? extends IInvokedMethodListener>, IInvokedMethodListener>
      invokedMethodListeners;

  private final SuiteRunState suiteState = new SuiteRunState();
  private final IAttributes attributes = new Attributes();
  private final Set<IExecutionVisualiser> visualisers = Sets.newHashSet();
  private final ITestListener exitCodeListener;

  public SuiteRunner(
      IConfiguration configuration,
      XmlSuite suite,
      String outputDir,
      ITestRunnerFactory runnerFactory,
      Comparator<ITestNGMethod> comparator) {
    this(configuration, suite, outputDir, runnerFactory, false, comparator);
  }

  public SuiteRunner(
      IConfiguration configuration,
      XmlSuite suite,
      String outputDir,
      ITestRunnerFactory runnerFactory,
      boolean useDefaultListeners,
      Comparator<ITestNGMethod> comparator) {
    this(
        configuration,
        suite,
        outputDir,
        runnerFactory,
        useDefaultListeners,
        new ArrayList<>() /* method interceptor */,
        null /* invoked method listeners */,
        new TestListenersContainer() /* test listeners */,
        null /* class listeners */,
        new DataProviderHolder(configuration),
        comparator);
  }

  protected SuiteRunner(
      IConfiguration configuration,
      XmlSuite suite,
      String outputDir,
      ITestRunnerFactory runnerFactory,
      boolean useDefaultListeners,
      List<IMethodInterceptor> methodInterceptors,
      Collection<IInvokedMethodListener> invokedMethodListener,
      TestListenersContainer container,
      Collection<IClassListener> classListeners,
      DataProviderHolder holder,
      Comparator<ITestNGMethod> comparator) {
    if (comparator == null) {
      throw new IllegalArgumentException("comparator must not be null");
    }
    this.holder = holder;
    this.configuration = configuration;
    this.xmlSuite = suite;
    this.useDefaultListeners = useDefaultListeners;
    this.tmpRunnerFactory = runnerFactory;
    this.exitCodeListener = container.exitCodeListener;
    List<IMethodInterceptor> localMethodInterceptors =
        Optional.ofNullable(methodInterceptors).orElse(Lists.newArrayList());
    setOutputDir(outputDir);
    if (configuration.getObjectFactory() == null) {
      configuration.setObjectFactory(new ObjectFactoryImpl());
    }
    if (suite.getObjectFactoryClass() == null) {
      objectFactory = configuration.getObjectFactory();
    } else {
      boolean create =
          !configuration.getObjectFactory().getClass().equals(suite.getObjectFactoryClass());
      final ITestObjectFactory suiteObjectFactory;
      if (create) {
        if (objectFactory == null) {
          objectFactory = configuration.getObjectFactory();
        }
        // Dont keep creating the object factory repeatedly since our current object factory
        // Was already created based off of a suite level object factory.
        suiteObjectFactory = objectFactory.newInstance(suite.getObjectFactoryClass());
      } else {
        suiteObjectFactory = configuration.getObjectFactory();
      }
      objectFactory =
          new ITestObjectFactory() {
            @Override
            public <T> T newInstance(Class<T> cls, Object... parameters) {
              try {
                return suiteObjectFactory.newInstance(cls, parameters);
              } catch (Exception e) {
                return configuration.getObjectFactory().newInstance(cls, parameters);
              }
            }

            @Override
            public <T> T newInstance(String clsName, Object... parameters) {
              try {
                return suiteObjectFactory.newInstance(clsName, parameters);
              } catch (Exception e) {
                return configuration.getObjectFactory().newInstance(clsName, parameters);
              }
            }

            @Override
            public <T> T newInstance(Constructor<T> constructor, Object... parameters) {
              try {
                return suiteObjectFactory.newInstance(constructor, parameters);
              } catch (Exception e) {
                return configuration.getObjectFactory().newInstance(constructor, parameters);
              }
            }
          };
    }
    // Add our own IInvokedMethodListener
    invokedMethodListeners = Maps.synchronizedLinkedHashMap();
    for (IInvokedMethodListener listener :
        Optional.ofNullable(invokedMethodListener).orElse(Collections.emptyList())) {
      invokedMethodListeners.put(listener.getClass(), listener);
    }

    skipFailedInvocationCounts = suite.skipFailedInvocationCounts();
    this.testListeners.addAll(container.listeners);
    for (IClassListener classListener :
        Optional.ofNullable(classListeners).orElse(Collections.emptyList())) {
      this.classListeners.put(classListener.getClass(), classListener);
    }
    ITestRunnerFactory iTestRunnerFactory = buildRunnerFactory(comparator);

    // Order the <test> tags based on their order of appearance in testng.xml
    List<XmlTest> xmlTests = xmlSuite.getTests();
    xmlTests.sort(Comparator.comparingInt(XmlTest::getIndex));

    for (XmlTest test : xmlTests) {
      TestRunner tr =
          iTestRunnerFactory.newTestRunner(
              this,
              test,
              invokedMethodListeners.values(),
              Lists.newArrayList(this.classListeners.values()),
              this.holder);

      //
      // Install the method interceptor, if any was passed
      //
      for (IMethodInterceptor methodInterceptor : localMethodInterceptors) {
        tr.addMethodInterceptor(methodInterceptor);
      }

      testRunners.add(tr);
    }
  }

  @Override
  public XmlSuite getXmlSuite() {
    return xmlSuite;
  }

  @Override
  public String getName() {
    return xmlSuite.getName();
  }

  public void setObjectFactory(ITestObjectFactory objectFactory) {
    this.objectFactory = objectFactory;
  }

  public void setReportResults(boolean reportResults) {
    useDefaultListeners = reportResults;
  }

  public ITestListener getExitCodeListener() {
    return exitCodeListener;
  }

  private void invokeListeners(boolean start) {
    if (start) {
      for (ISuiteListener sl :
          ListenerOrderDeterminer.order(
              listeners.values(), this.configuration.getListenerComparator())) {
        sl.onStart(this);
      }
    } else {
      Utils.log("Suite finish");
      List<ISuiteListener> suiteListenersReversed =
          ListenerOrderDeterminer.reversedOrder(
              listeners.values(), this.configuration.getListenerComparator());
      for (ISuiteListener sl : suiteListenersReversed) {
        Utils.log("Invoke suite listener: " + sl);
        sl.onFinish(this);
        Utils.log("Finish Invoke suite listener: " + sl);
      }
    }
  }

  private void setOutputDir(String outputdir) {
    if (isStringBlank(outputdir) && useDefaultListeners) {
      outputdir = DEFAULT_OUTPUT_DIR;
    }

    outputDir = (null != outputdir) ? new File(outputdir).getAbsolutePath() : null;
  }

  private ITestRunnerFactory buildRunnerFactory(Comparator<ITestNGMethod> comparator) {
    ITestRunnerFactory factory;

    if (null == tmpRunnerFactory) {
      factory =
          new DefaultTestRunnerFactory(
              configuration,
              testListeners.toArray(new ITestListener[0]),
              useDefaultListeners,
              skipFailedInvocationCounts,
              comparator,
              this);
    } else {
      factory =
          new ProxyTestRunnerFactory(
              testListeners.toArray(new ITestListener[0]), tmpRunnerFactory, configuration);
    }

    return factory;
  }

  @Override
  public String getParallel() {
    return xmlSuite.getParallel().toString();
  }

  @Override
  public String getParentModule() {
    return xmlSuite.getParentModule();
  }

  @Override
  public String getGuiceStage() {
    return xmlSuite.getGuiceStage();
  }

  @Override
  public Injector getParentInjector() {
    return parentInjector;
  }

  @Override
  public void setParentInjector(Injector injector) {
    parentInjector = injector;
  }

  @Override
  public void run() {
    invokeListeners(true /* start */);
    try {
      Utils.log("Private Run start");
      privateRun();
      Utils.log("Private Run end");
    } catch (Exception e) {
      Utils.log("Private Run EXCEPTION " + e.getMessage());
    }
    finally {
      Utils.log("Before before listeners");
      invokeListeners(false /* stop */);
      Utils.log("After after listeners");
    }
  }

  private void privateRun() {

    // Map for unicity, Linked for guaranteed order
    Map<Method, ITestNGMethod> beforeSuiteMethods = new LinkedHashMap<>();
    Map<Method, ITestNGMethod> afterSuiteMethods = new LinkedHashMap<>();

    IInvoker invoker = null;

    // Get the invoker and find all the suite level methods
    for (TestRunner tr : testRunners) {
      // TODO: Code smell.  Invoker should belong to SuiteRunner, not TestRunner
      // -- cbeust
      invoker = tr.getInvoker();

      // Add back the configuration listeners that may have gotten altered after
      // our suite level listeners were invoked.
      this.configuration.getConfigurationListeners().forEach(tr::addConfigurationListener);

      for (ITestNGMethod m : tr.getBeforeSuiteMethods()) {
        beforeSuiteMethods.put(m.getConstructorOrMethod().getMethod(), m);
      }

      for (ITestNGMethod m : tr.getAfterSuiteMethods()) {
        afterSuiteMethods.put(m.getConstructorOrMethod().getMethod(), m);
      }
    }

    //
    // Invoke beforeSuite methods (the invoker can be null
    // if the suite we are currently running only contains
    // a <file-suite> tag and no real tests)
    //
    if (invoker != null) {
      if (!beforeSuiteMethods.values().isEmpty()) {
        ConfigMethodArguments arguments =
            new Builder()
                .usingConfigMethodsAs(beforeSuiteMethods.values())
                .forSuite(xmlSuite)
                .usingParameters(xmlSuite.getParameters())
                .build();
        invoker.getConfigInvoker().invokeConfigurations(arguments);
      }

      Utils.log("SuiteRunner", 3, "Created " + testRunners.size() + " TestRunners");

      //
      // Run all the test runners
      //
      boolean testsInParallel = XmlSuite.ParallelMode.TESTS.equals(xmlSuite.getParallel());
      if (RuntimeBehavior.strictParallelism()) {
        testsInParallel = !XmlSuite.ParallelMode.NONE.equals(xmlSuite.getParallel());
      }
      if (testsInParallel) {
        Utils.log("SuiteRunner", 3, "Running " + testRunners.size() + " TestRunners");
        Utils.log("Tests in parallel");
        runInParallelTestMode();
        Utils.log("After run in parallel");
      } else {
        runSequentially();
      }

      //
      // Invoke afterSuite methods
      //
      Utils.log("Invoke afterSuite methods");
      if (!afterSuiteMethods.values().isEmpty()) {
        Utils.log("Invoke afterSuite methods " + afterSuiteMethods.values().stream().map(ITestNGMethod::getMethodName).collect(Collectors.toList()));
        ConfigMethodArguments arguments =
            new Builder()
                .usingConfigMethodsAs(afterSuiteMethods.values())
                .forSuite(xmlSuite)
                .usingParameters(xmlSuite.getAllParameters())
                .build();
        invoker.getConfigInvoker().invokeConfigurations(arguments);
        Utils.log("Done invoking afterSuite methods");
      }
    }
  }

  private void addVisualiser(IExecutionVisualiser visualiser) {
    visualisers.add(visualiser);
  }

  private void addReporter(IReporter listener) {
    reporters.add(listener);
  }

  void addConfigurationListener(IConfigurationListener listener) {
    configuration.addConfigurationListener(listener);
  }

  public List<IReporter> getReporters() {
    return reporters;
  }

  public Collection<IDataProviderListener> getDataProviderListeners() {
    return this.holder.getListeners();
  }

  private void runSequentially() {
    for (TestRunner tr : testRunners) {
      runTest(tr);
    }
  }

  private final AutoCloseableLock suiteResultsLock = new AutoCloseableLock();

  private void runTest(TestRunner tr) {
    Utils.log("Run test in suite runner");
    visualisers.forEach(tr::addListener);
    Utils.log("Visualisers...");
    Utils.log("Tr runner: " + tr);
    tr.run();
    Utils.log("Tr runner finished: " + tr);

    ISuiteResult sr = new SuiteResult(xmlSuite, tr);
    Utils.log("Suite result " + sr);
    try (AutoCloseableLock ignore = suiteResultsLock.lock()) {
      Utils.log("Suite result2 " + sr);
      suiteResults.put(tr.getName(), sr);
      Utils.log("Suite result3 " + sr);
    }
  }

  /**
   * Implement <suite parallel="tests">. Since this kind of parallelism happens at the suite level,
   * we need a special code path to execute it. All the other parallelism strategies are implemented
   * at the test level in TestRunner#createParallelWorkers (but since this method deals with just
   * one &lt;test&gt; tag, it can't implement <suite parallel="tests">, which is why we're doing it
   * here).
   */
  private void runInParallelTestMode() {
    List<Runnable> tasks = Lists.newArrayList(testRunners.size());
    for (TestRunner tr : testRunners) {
      tasks.add(new SuiteWorker(tr));
    }

    Utils.log("Run in parallel");
    ThreadUtil.execute(
        configuration,
        "tests",
        tasks,
        xmlSuite.getThreadCount(),
        xmlSuite.getTimeOut(XmlTest.DEFAULT_TIMEOUT_MS));
    Utils.log("Done run in parallel");
  }

  private class SuiteWorker implements Runnable {
    private final TestRunner testRunner;

    public SuiteWorker(TestRunner tr) {
      testRunner = tr;
    }

    @Override
    public void run() {
      Utils.log(
          "[SuiteWorker]",
          4,
          "Running XML Test '" + testRunner.getTest().getName() + "' in Parallel");
      runTest(testRunner);
    }
  }

  /** @param reporter The ISuiteListener interested in reporting the result of the current suite. */
  protected void addListener(ISuiteListener reporter) {
    listeners.putIfAbsent(reporter.getClass(), reporter);
  }

  @Override
  public void addListener(ITestNGListener listener) {
    if (listener instanceof IInvokedMethodListener) {
      IInvokedMethodListener invokedMethodListener = (IInvokedMethodListener) listener;
      invokedMethodListeners.put(invokedMethodListener.getClass(), invokedMethodListener);
    }
    if (listener instanceof ISuiteListener) {
      addListener((ISuiteListener) listener);
    }
    if (listener instanceof IExecutionVisualiser) {
      addVisualiser((IExecutionVisualiser) listener);
    }
    if (listener instanceof IReporter) {
      addReporter((IReporter) listener);
    }
    if (listener instanceof IConfigurationListener) {
      addConfigurationListener((IConfigurationListener) listener);
    }
    if (listener instanceof IClassListener) {
      IClassListener classListener = (IClassListener) listener;
      classListeners.putIfAbsent(classListener.getClass(), classListener);
    }
    if (listener instanceof IDataProviderListener) {
      IDataProviderListener listenerObject = (IDataProviderListener) listener;
      this.holder.addListener(listenerObject);
    }
    if (listener instanceof IDataProviderInterceptor) {
      IDataProviderInterceptor interceptor = (IDataProviderInterceptor) listener;
      this.holder.addInterceptor(interceptor);
    }
    if (listener instanceof ITestListener) {
      for (TestRunner testRunner : testRunners) {
        testRunner.addTestListener((ITestListener) listener);
      }
    }
  }

  @Override
  public String getOutputDirectory() {
    return outputDir + File.separatorChar + getName();
  }

  @Override
  public Map<String, ISuiteResult> getResults() {
    // Just to ensure that we guard the internals of the suite results we now wrap it
    // around with an unmodifiable map.
    return Collections.unmodifiableMap(suiteResults);
  }

  /**
   * FIXME: should be removed?
   *
   * @see org.testng.ISuite#getParameter(java.lang.String)
   */
  @Override
  public String getParameter(String parameterName) {
    return xmlSuite.getParameter(parameterName);
  }

  /** @see org.testng.ISuite#getMethodsByGroups() */
  @Override
  public Map<String, Collection<ITestNGMethod>> getMethodsByGroups() {
    Map<String, Collection<ITestNGMethod>> result = Maps.newHashMap();

    for (TestRunner tr : testRunners) {
      ITestNGMethod[] methods = tr.getAllTestMethods();
      for (ITestNGMethod m : methods) {
        String[] groups = m.getGroups();
        for (String groupName : groups) {
          Collection<ITestNGMethod> testMethods =
              result.computeIfAbsent(groupName, k -> Lists.newArrayList());
          testMethods.add(m);
        }
      }
    }

    return result;
  }

  /** @see org.testng.ISuite#getExcludedMethods() */
  @Override
  public Collection<ITestNGMethod> getExcludedMethods() {
    return testRunners.stream()
        .flatMap(tr -> tr.getExcludedMethods().stream())
        .collect(Collectors.toList());
  }

  @Override
  public ITestObjectFactory getObjectFactory() {
    return objectFactory;
  }

  /**
   * Returns the annotation finder for the given annotation type.
   *
   * @return the annotation finder for the given annotation type.
   */
  @Override
  public IAnnotationFinder getAnnotationFinder() {
    return configuration.getAnnotationFinder();
  }

  /** The default implementation of {@link ITestRunnerFactory}. */
  private static class DefaultTestRunnerFactory implements ITestRunnerFactory {
    private final ITestListener[] failureGenerators;
    private final boolean useDefaultListeners;
    private final boolean skipFailedInvocationCounts;
    private final IConfiguration configuration;
    private final Comparator<ITestNGMethod> comparator;
    private final SuiteRunner suiteRunner;

    public DefaultTestRunnerFactory(
        IConfiguration configuration,
        ITestListener[] failureListeners,
        boolean useDefaultListeners,
        boolean skipFailedInvocationCounts,
        Comparator<ITestNGMethod> comparator,
        SuiteRunner suiteRunner) {
      this.configuration = configuration;
      this.failureGenerators = failureListeners;
      this.useDefaultListeners = useDefaultListeners;
      this.skipFailedInvocationCounts = skipFailedInvocationCounts;
      this.comparator = comparator;
      this.suiteRunner = suiteRunner;
    }

    @Override
    public TestRunner newTestRunner(
        ISuite suite,
        XmlTest test,
        Collection<IInvokedMethodListener> listeners,
        List<IClassListener> classListeners) {
      return newTestRunner(suite, test, listeners, classListeners, Collections.emptyMap());
    }

    @Override
    public TestRunner newTestRunner(
        ISuite suite,
        XmlTest test,
        Collection<IInvokedMethodListener> listeners,
        List<IClassListener> classListeners,
        Map<Class<? extends IDataProviderListener>, IDataProviderListener> dataProviderListeners) {
      DataProviderHolder holder = new DataProviderHolder(this.configuration);
      holder.addListeners(dataProviderListeners.values());
      return newTestRunner(suite, test, listeners, classListeners, holder);
    }

    @Override
    public TestRunner newTestRunner(
        ISuite suite,
        XmlTest test,
        Collection<IInvokedMethodListener> listeners,
        List<IClassListener> classListeners,
        DataProviderHolder holder) {
      boolean skip = skipFailedInvocationCounts;
      if (!skip) {
        skip = test.skipFailedInvocationCounts();
      }
      TestRunner testRunner =
          new TestRunner(
              configuration,
              suite,
              test,
              suite.getOutputDirectory(),
              suite.getAnnotationFinder(),
              skip,
              listeners,
              classListeners,
              comparator,
              holder,
              suiteRunner);

      if (useDefaultListeners) {
        testRunner.addListener(new TestHTMLReporter());
        testRunner.addListener(new JUnitXMLReporter());

        // TODO: Moved these here because maven2 has output reporters running
        // already, the output from these causes directories to be created with
        // files. This is not the desired behaviour of running tests in maven2.
        // Don't know what to do about this though, are people relying on these
        // to be added even with defaultListeners set to false?
        testRunner.addListener(new TextReporter(testRunner.getName(), TestRunner.getVerbose()));
      }

      for (ITestListener itl : failureGenerators) {
        testRunner.addTestListener(itl);
      }
      for (IConfigurationListener cl : configuration.getConfigurationListeners()) {
        testRunner.addConfigurationListener(cl);
      }

      return testRunner;
    }
  }

  private static class ProxyTestRunnerFactory implements ITestRunnerFactory {
    private final ITestListener[] failureGenerators;
    private final ITestRunnerFactory target;

    private final IConfiguration configuration;

    public ProxyTestRunnerFactory(
        ITestListener[] failureListeners, ITestRunnerFactory target, IConfiguration configuration) {
      failureGenerators = failureListeners;
      this.target = target;
      this.configuration = configuration;
    }

    @Override
    public TestRunner newTestRunner(
        ISuite suite,
        XmlTest test,
        Collection<IInvokedMethodListener> listeners,
        List<IClassListener> classListeners) {
      return newTestRunner(suite, test, listeners, classListeners, Collections.emptyMap());
    }

    @Override
    public TestRunner newTestRunner(
        ISuite suite,
        XmlTest test,
        Collection<IInvokedMethodListener> listeners,
        List<IClassListener> classListeners,
        Map<Class<? extends IDataProviderListener>, IDataProviderListener> dataProviderListeners) {
      DataProviderHolder holder = new DataProviderHolder(configuration);
      holder.addListeners(dataProviderListeners.values());
      return newTestRunner(suite, test, listeners, classListeners, holder);
    }

    @Override
    public TestRunner newTestRunner(
        ISuite suite,
        XmlTest test,
        Collection<IInvokedMethodListener> listeners,
        List<IClassListener> classListeners,
        DataProviderHolder holder) {
      TestRunner testRunner = target.newTestRunner(suite, test, listeners, classListeners, holder);
      testRunner.addListener(new TextReporter(testRunner.getName(), TestRunner.getVerbose()));

      for (ITestListener itl : failureGenerators) {
        testRunner.addListener(itl);
      }
      return testRunner;
    }
  }

  public void setHost(String host) {
    remoteHost = host;
  }

  @Override
  public String getHost() {
    return remoteHost;
  }

  /** @see org.testng.ISuite#getSuiteState() */
  @Override
  public SuiteRunState getSuiteState() {
    return suiteState;
  }

  public void setSkipFailedInvocationCounts(Boolean skipFailedInvocationCounts) {
    if (skipFailedInvocationCounts != null) {
      this.skipFailedInvocationCounts = skipFailedInvocationCounts;
    }
  }

  @Override
  public Object getAttribute(String name) {
    return attributes.getAttribute(name);
  }

  @Override
  public void setAttribute(String name, Object value) {
    attributes.setAttribute(name, value);
  }

  @Override
  public Set<String> getAttributeNames() {
    return attributes.getAttributeNames();
  }

  @Override
  public Object removeAttribute(String name) {
    return attributes.removeAttribute(name);
  }

  /////
  // implements IInvokedMethodListener
  //

  @Override
  public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
    // Empty implementation.
  }

  @Override
  public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
    if (method == null) {
      throw new NullPointerException("Method should not be null");
    }
    if (method.getTestMethod() instanceof IInvocationStatus) {
      ((IInvocationStatus) method.getTestMethod()).setInvokedAt(method.getDate());
    }
  }

  //
  // implements IInvokedMethodListener
  /////

  @Override
  public List<IInvokedMethod> getAllInvokedMethods() {
    return testRunners.stream()
        .flatMap(
            tr -> {
              Set<ITestResult> results = new HashSet<>();
              results.addAll(tr.getConfigurationsScheduledForInvocation().getAllResults());
              results.addAll(tr.getPassedConfigurations().getAllResults());
              results.addAll(tr.getFailedConfigurations().getAllResults());
              results.addAll(tr.getSkippedConfigurations().getAllResults());
              results.addAll(tr.getPassedTests().getAllResults());
              results.addAll(tr.getFailedTests().getAllResults());
              results.addAll(tr.getFailedButWithinSuccessPercentageTests().getAllResults());
              results.addAll(tr.getSkippedTests().getAllResults());
              return results.stream();
            })
        .filter(tr -> tr.getMethod() instanceof IInvocationStatus)
        .filter(tr -> ((IInvocationStatus) tr.getMethod()).getInvocationTime() > 0)
        .map(tr -> new InvokedMethod(((IInvocationStatus) tr.getMethod()).getInvocationTime(), tr))
        .collect(Collectors.toList());
  }

  @Override
  public List<ITestNGMethod> getAllMethods() {
    return this.testRunners.stream()
        .flatMap(tr -> Arrays.stream(tr.getAllTestMethods()))
        .collect(Collectors.toList());
  }

  static class TestListenersContainer {
    private final List<ITestListener> listeners = Lists.newArrayList();
    private final ITestListener exitCodeListener;

    TestListenersContainer() {
      this(Collections.emptyList(), null);
    }

    TestListenersContainer(List<ITestListener> listeners, ITestListener exitCodeListener) {
      this.listeners.addAll(listeners);
      this.exitCodeListener =
          Objects.requireNonNullElseGet(exitCodeListener, () -> new ITestListener() {});
    }
  }
}
