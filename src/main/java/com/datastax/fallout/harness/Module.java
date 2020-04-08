/*
 * Copyright 2020 DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.fallout.harness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import clojure.lang.APersistentSet;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import io.netty.util.HashedWheelTimer;
import jepsen.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.fallout.ops.ConfigurationManager;
import com.datastax.fallout.ops.Ensemble;
import com.datastax.fallout.ops.Product;
import com.datastax.fallout.ops.PropertyGroup;
import com.datastax.fallout.ops.PropertySpec;
import com.datastax.fallout.ops.PropertySpecBuilder;
import com.datastax.fallout.ops.Provider;
import com.datastax.fallout.ops.Utils;
import com.datastax.fallout.ops.WritablePropertyGroup;
import com.datastax.fallout.util.NamedThreadFactory;

import static com.datastax.fallout.harness.ClojureApi.conj;
import static com.datastax.fallout.harness.ClojureApi.deref;
import static com.datastax.fallout.harness.ClojureApi.get;
import static com.datastax.fallout.harness.ClojureApi.swap;
import static com.datastax.fallout.harness.JepsenApi.*;

/**
 * Modules are one of the core runtime abstractions of a test. Modules contain code that is invoked during the runtime
 * of the test. The testing code belongs in invoke - the setup and teardown methods are hooks that will run at the start
 * and end of a test.
 *
 * Presently, we use the Jepsen testing framework as our runtime. Jepsen uses the jepsen.client.Client interface. This
 * interface is automatically generated by the corresponding Clojure protocol. This interface's methods are *_BANG_
 * methods and take Objects as arguments. To protect this interop boundary, we expect users to implement the
 * setup, etc methods, which will be called by our setup_BANG_ methods after interpreting these objects.
 */
public abstract class Module implements Client, WorkloadComponent
{
    /** How long the module will run for */
    protected enum Lifetime
    {
        /** The module will run once */
        RUN_ONCE,

        /** The module will run until all other {@link #RUN_ONCE} modules in this phase are
         *  complete; see {@link RunToEndOfPhaseMethod} for how this will be achieved. */
        RUN_TO_END_OF_PHASE;

        /** Lazy version of valueOf that returns the first enum value that matches the end of str */
        static Lifetime lazyValueOf(String str)
        {
            return Arrays.stream(Lifetime.values())
                .filter(val -> val.toString().endsWith(str.toUpperCase()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(String.format("'%s' is not a valid Lifetime", str)));
        }
    }

    /** How {@link #run} is expected to behave if {@link #lifetime} is {@link Lifetime#RUN_TO_END_OF_PHASE} */
    protected enum RunToEndOfPhaseMethod
    {
        /** {@link #run} will be called once; it's expected to check {@link #runsToEndOfPhase()} to
         *  see if its supposed to run to end of phase, and return once {@link #getUnfinishedRunOnceModules}
         *  returns 0; there's nothing to stop it using other criteria though */
        MANUAL,

        /** If {@link #runsToEndOfPhase()} is true, {@link #run} will be called
         *  repeatedly until {@link #getUnfinishedRunOnceModules()} is 0. */
        AUTOMATIC
    }

    // Don't change these without considering their use in timestamp placeholders.
    public static final String START_EVENT_PREFIX = "Start: ";
    public static final String END_EVENT_PREFIX = "End: ";

    private static final Logger classLogger = LoggerFactory.getLogger(Module.class);
    protected static final HashedWheelTimer timer = new HashedWheelTimer(new NamedThreadFactory("ModuleTimer"));

    // Set in setup_BANG_ since Jepsen uses a Clojure binding for this, which doesn't migrate across threads.
    // As long as we don't update our Jepsen version, we know the binding will be present on the thread running
    // setup_BANG_.
    private volatile long relativeTimeOrigin;

    private PropertyGroup moduleInstanceProperties;
    private volatile String modulePhaseName;

    // if false, module's setup/teardown will run immediately before/after run, respectively
    protected boolean useGlobalSetupTeardown = false;

    // Optional containing a reference to the test, initialized in invoke so we can use emit
    private volatile Optional<Object> test = Optional.empty();

    protected Optional<String> testRunId = Optional.empty();

    // True when a Module is in its run method
    protected boolean running = false;

    // Atomic counter for number of times emit has been called in a given run
    protected final AtomicInteger emittedCount = new AtomicInteger();

    protected Logger logger = classLogger;

    private boolean setupSucceeded = false;

    private final RunToEndOfPhaseMethod runToEndOfPhaseMethod;
    private final PropertySpec<Lifetime> lifetimePropertySpec;

    private Lifetime lifetime;
    private int runOnceModules = 0;
    private final List<Runnable> completionCallbacks = new ArrayList<>();
    private CountDownLatch unfinishedRunOnceModules;

    private Optional<Supplier<Boolean>> abortedCheck = Optional.empty();

    public Module()
    {
        this(RunToEndOfPhaseMethod.AUTOMATIC, Lifetime.RUN_ONCE);
    }

    /** Call from a sub-class' constructor to set hard-coded lifetime behaviour.  If this isn't called,
     *  lifetime becomes a run-time property settable in YAML.
     */
    protected Module(RunToEndOfPhaseMethod runToEndOfPhaseMethod)
    {
        this(runToEndOfPhaseMethod, Lifetime.RUN_TO_END_OF_PHASE, false);
    }

    /** Call from a sub-class' constructor to set user-selectable lifetime
     *  behavior via the lifetime run-time property, with a default of lifetime */
    protected Module(RunToEndOfPhaseMethod runToEndOfPhaseMethod, Lifetime defaultLifetime)
    {
        this(runToEndOfPhaseMethod, defaultLifetime, true);
    }

    private Module(RunToEndOfPhaseMethod runToEndOfPhaseMethod, Lifetime defaultLifetime,
        boolean createLifetimePropertySpec)
    {
        this.runToEndOfPhaseMethod = runToEndOfPhaseMethod;
        this.lifetime = defaultLifetime;
        if (createLifetimePropertySpec)
        {
            this.lifetimePropertySpec = PropertySpecBuilder.<Lifetime>create(prefix())
                .name("lifetime")
                .description("Whether the module should be run_once, in which case it will run once and exit, " +
                    "or whether it should run_to_end_of_phase, in which case it will run until all other modules " +
                    "in the phase are complete.  You can abbreviate 'run_once' and 'run_to_end_of_phase' to 'once' " +
                    "and 'phase'")
                .defaultOf(defaultLifetime)
                .suggestions(Lifetime.values())
                .parser(obj -> Lifetime.lazyValueOf(String.valueOf(obj)))
                .build();
        }
        else
        {
            this.lifetimePropertySpec = null;
        }
    }

    public void setTestRunAbortedCheck(Supplier<Boolean> abortedCheck)
    {
        this.abortedCheck = Optional.ofNullable(abortedCheck);
    }

    protected boolean isTestRunAborted()
    {
        boolean aborted = abortedCheck.map(Supplier::get).orElse(false);
        if (aborted)
        {
            logger().warn("Module " + name() + "(" + getInstanceName() + ") returns early for aborted test run.");
        }
        return aborted;
    }

    protected CountDownLatch getUnfinishedRunOnceModules()
    {
        return unfinishedRunOnceModules;
    }

    public void addCompletionCallback(Runnable callback)
    {
        completionCallbacks.add(callback);
    }

    private void runCompletionCallbacks()
    {
        completionCallbacks.forEach(Runnable::run);
        completionCallbacks.clear();
    }

    void addRunOnceModule(Module runOnceModule)
    {
        Preconditions.checkState(runsToEndOfPhase());
        Preconditions.checkArgument(!runOnceModule.runsToEndOfPhase());
        runOnceModules++;

        runOnceModule.addCompletionCallback(() -> unfinishedRunOnceModules.countDown());
    }

    public boolean runsToEndOfPhase()
    {
        return lifetime == Lifetime.RUN_TO_END_OF_PHASE;
    }

    public boolean hasPhaseLifetime()
    {
        return !hasDynamicLifetime();
    }

    public boolean hasDynamicLifetime()
    {
        return lifetimePropertySpec != null;
    }

    /**
     * List the providers a module requires to work.
     *
     * This is an explicit call so we can verify upfront
     * if a ops config will work with this module.
     *
     * @see ConfigurationManager#getAvailableProviders(PropertyGroup)
     *
     * @return the list of providers this Module requires
     */
    public abstract Set<Class<? extends Provider>> getRequiredProviders();

    /**
     * List the products a module supports testing
     *
     * We need to verify upfront if a config will work with this module
     *
     * @return the list of products this Module supports
     */
    public abstract List<Product> getSupportedProducts();

    /**
     * Returns the contents of a resource specific to the java package name
     *
     * @param resourceName
     * @return
     */
    public Optional<byte[]> getResource(String resourceName)
    {
        return Utils.getResource(this, resourceName);
    }

    @Override
    final public List<PropertySpec> getPropertySpecs()
    {
        final ImmutableList.Builder<PropertySpec> builder = ImmutableList.<PropertySpec>builder()
            .addAll(getModulePropertySpecs());
        if (lifetimePropertySpec != null)
        {
            builder.add(lifetimePropertySpec);
        }
        return builder.build();
    }

    protected List<PropertySpec> getModulePropertySpecs()
    {
        return Collections.emptyList();
    }

    @Override
    public void setProperties(PropertyGroup properties)
    {
        Preconditions.checkArgument(moduleInstanceProperties == null, "module instance properties already set");
        moduleInstanceProperties = properties;
        if (lifetimePropertySpec != null)
        {
            lifetime = lifetimePropertySpec.value(properties);
        }
    }

    @Override
    public PropertyGroup getProperties()
    {
        return moduleInstanceProperties != null ? moduleInstanceProperties : new WritablePropertyGroup();
    }

    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    protected Logger logger()
    {
        return logger;
    }

    @Override
    public void setInstanceName(String modulePhaseName)
    {
        Preconditions.checkArgument(this.modulePhaseName == null, "module phase name already set");
        this.modulePhaseName = modulePhaseName;
    }

    @Override
    public String getInstanceName()
    {
        return modulePhaseName;
    }

    private interface SetupOrTeardown
    {
        void setupOrTeardown(Module module, Ensemble ensemble, PropertyGroup properties);
    }

    private boolean doSafely(SetupOrTeardown setupOrTeardown, String stage, Ensemble ensemble, PropertyGroup properties)
    {
        try
        {
            setupOrTeardown.setupOrTeardown(this, ensemble, properties);
            return true;
        }
        catch (Throwable e)
        {
            logger().error("Exception in module " + getInstanceName() + " " + stage, e);
            emit(Operation.Type.error, e);
            return false;
        }
    }

    @Override
    public Object setup_BANG_(Object test, Object node)
    {
        this.relativeTimeOrigin = (Long) ClojureApi.deref.invoke(JepsenApi.relativeTimeOriginBinding);
        Ensemble ensemble = (Ensemble) get.invoke(test, ENSEMBLE);

        this.test = Optional.ofNullable(test);
        this.testRunId = Optional.ofNullable(ensemble.getTestRunId());

        if (useGlobalSetupTeardown)
        {
            setLogger(ensemble.getControllerGroup().logger());
            setupSucceeded = doSafely(Module::setup, "setup", ensemble, getProperties());
        }

        if (runsToEndOfPhase())
        {
            unfinishedRunOnceModules = new CountDownLatch(runOnceModules);
        }

        return this;
    }

    /**
     * Hook to set up module - can occur at beginning of test or before invoke depending on
     * useGlobalSetupTeardown
     * @param ensemble
     * @param properties
     */
    public void setup(Ensemble ensemble, PropertyGroup properties)
    {
    }

    @Override
    public Object invoke_BANG_(Object test, Object op)
    {
        Ensemble ensemble = (Ensemble) get.invoke(test, ENSEMBLE);

        running = true;

        if (!useGlobalSetupTeardown)
        {
            setLogger(ensemble.getControllerGroup().logger());
            setupSucceeded = doSafely(Module::setup, "setup", ensemble, getProperties());
        }

        try
        {
            if (setupSucceeded)
            {
                runModule(ensemble);
            }
        }
        catch (Exception e)
        {
            logger().error("Exception in module " + getInstanceName() + " run", e);
            emit(Operation.Type.error, e);
        }
        finally
        {
            if (!useGlobalSetupTeardown)
            {
                doSafely(Module::teardown, "teardown", ensemble, getProperties());
            }

            emit(Operation.Type.end);

            running = false;

            emittedCount.set(0);
        }

        runCompletionCallbacks();

        return ((IPersistentMap) op)
            .assoc(TYPE, INFO)
            .assoc(MEDIATYPE, MediaType.PLAIN_TEXT_UTF_8)
            .assoc(VALUE, "completed");
    }

    private void runModule(Ensemble ensemble) throws Exception
    {
        if (lifetime == Lifetime.RUN_ONCE)
        {
            run(ensemble, getProperties());

            if (emittedCount.get() == 0)
                emitError("No Operations were emitted during run");
        }
        else
        {
            do
            {
                run(ensemble, getProperties());
            }
            while (runToEndOfPhaseMethod == RunToEndOfPhaseMethod.AUTOMATIC &&
                unfinishedRunOnceModules.getCount() != 0);

            unfinishedRunOnceModules.await();
        }
    }

    /**
     * Used to obtain the time in nanoseconds since Jepsen started executing this test.
     *
     * In most forms of emit, this is used to timestamp operations as they are emitted
     * and is invoked implicitly. We provide this method to allow this timesource
     * to be used in other fashions.
     *
     * @return the time in nanoseconds relative to Jepsen starting this test
     */
    protected long nanotimeRelativeToJepsenStart()
    {
        return System.nanoTime() - relativeTimeOrigin;
    }

    public void emitInfo(Object value)
    {
        emit(Operation.Type.info, value);
    }

    public void emitInfo(String message)
    {
        emit(Operation.Type.info, MediaType.PLAIN_TEXT_UTF_8, message);
    }

    public void emitOk(String message)
    {
        emit(Operation.Type.ok, MediaType.PLAIN_TEXT_UTF_8, message);
    }

    public void emitError(String message)
    {
        emit(Operation.Type.error, MediaType.PLAIN_TEXT_UTF_8, message);
    }

    public void emitFail(String message)
    {
        emit(Operation.Type.fail, MediaType.PLAIN_TEXT_UTF_8, message);
    }

    public void emitInvoke(String message)
    {
        emit(Operation.Type.invoke, MediaType.PLAIN_TEXT_UTF_8, message);
    }

    public void emit(Operation.Type type)
    {
        emit(type, null);
    }

    /**
     * Adds an Operation on to the history of the running test with default MediaType
     * @param type
     * @param value
     */
    public void emit(Operation.Type type, Object value)
    {
        emit(type, MediaType.OCTET_STREAM, value);
    }

    /**
     * Adds an Operation on to the history of the running test
     * @param type
     * @param mimeType
     * @param value
     */
    public void emit(Operation.Type type, MediaType mimeType, Object value)
    {
        emit(PersistentHashMap.EMPTY.assoc(TYPE, Keyword.intern(null, type.toString().toLowerCase()))
            .assoc(TIME, nanotimeRelativeToJepsenStart())
            .assoc(VALUE, value)
            .assoc(MEDIATYPE, mimeType));
        if (type.equals(Operation.Type.error))
        {
            logger().error("Emit fail - {}", value);
        }
        else
        {
            logger().info("Emit {} - {}", type, value);
        }
    }

    /**
     * Adds an existing Jepsen operation (in the form of Jepsen Operation) on to the history.
     * Adds only the process and module fields.
     * @param op
     */
    public void emit(IPersistentMap op)
    {
        if (!running)
            throw new RuntimeException("Module " + this.getInstanceName() + "tried to emit outside of its run method");

        if (!test.isPresent())
            throw new RuntimeException("Module " + this.getInstanceName() + " tried to emit without a test started");

        emittedCount.incrementAndGet();

        Object activeHistories = deref.invoke(get.invoke(test.get(), ACTIVEHISTORIES));

        Verify.verify(activeHistories instanceof APersistentSet);

        Object mediatype = op.containsKey(MEDIATYPE) ? op.valAt(MEDIATYPE) : MediaType.OCTET_STREAM;

        ((APersistentSet) activeHistories).stream()
            .forEach(history -> swap.invoke(history, conj,
                op.assoc(PROCESS, Keyword.intern(this.getInstanceName()))
                    .assoc(MODULE, this)
                    .assoc(MEDIATYPE, mediatype)));
    }

    /**
     * Runs a module.  If this module's lifetime is {@link Lifetime#RUN_TO_END_OF_PHASE}, then
     * this will be called according to the value of {@link RunToEndOfPhaseMethod}; see the
     * documentation for {@link RunToEndOfPhaseMethod}'s values for details.
     */
    public abstract void run(Ensemble ensemble, PropertyGroup properties);

    @Override
    public Object teardown_BANG_(Object test)
    {
        Ensemble ensemble = (Ensemble) get.invoke(test, ENSEMBLE);

        this.test = Optional.empty();

        if (useGlobalSetupTeardown)
        {
            doSafely(Module::teardown, "teardown", ensemble, getProperties());
        }

        return this;
    }

    /**
     * Hook to teardown module - can occur at end of test or end of invoke depending on
     * useGlobalSetupTeardown
     * @param ensemble
     * @param properties
     * @return
     */
    public void teardown(Ensemble ensemble, PropertyGroup properties)
    {

    }

    public String toString()
    {
        return name();
    }
}
