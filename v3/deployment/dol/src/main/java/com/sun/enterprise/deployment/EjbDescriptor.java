/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.deployment;

import static com.sun.enterprise.deployment.LifecycleCallbackDescriptor.CallbackType;
import com.sun.enterprise.deployment.runtime.IASEjbExtraDescriptors;
import com.sun.enterprise.deployment.types.*;
import com.sun.enterprise.deployment.util.*;
import com.sun.enterprise.deployment.util.InterceptorBindingTranslator.TranslationResults;
import com.sun.enterprise.util.LocalStringManagerImpl;
import org.glassfish.internal.api.Globals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This abstract class encapsulates the meta-information describing
 * Entity, Session and MessageDriven EJBs.
 *
 * @author Danny Coward
 * @author Sanjeev Krishnan
 */

public abstract class EjbDescriptor extends EjbAbstractDescriptor
        implements WritableJndiNameEnvironment,
        EjbReferenceContainer,
        ResourceEnvReferenceContainer,
        ResourceReferenceContainer,
        ServiceReferenceContainer,
        MessageDestinationReferenceContainer {
    /**
     * Indicates the bean will manage its own transactions.
     */
    final public static String BEAN_TRANSACTION_TYPE = "Bean";
    /**
     * Indicates the bean expects the server to manage its transactions.
     */
    final public static String CONTAINER_TRANSACTION_TYPE = "Container";

    // Used in <transaction-scope> element in XML
    final public static String LOCAL_TRANSACTION_SCOPE = "Local";
    final public static String DISTRIBUTED_TRANSACTION_SCOPE = "Distributed";

    protected String transactionType = null;
    protected boolean usesDefaultTransaction = false;
    private Hashtable methodContainerTransactions = null;
    private Hashtable permissionedMethodsByPermission = null;
    private HashMap methodPermissionsFromDD = null;
    private Set<EnvironmentProperty> environmentProperties =
            new HashSet<EnvironmentProperty>();
    private Set<EjbReference> ejbReferences =
            new HashSet<EjbReference>();
    private Set<JmsDestinationReferenceDescriptor> jmsDestReferences =
            new HashSet<JmsDestinationReferenceDescriptor>();
    private Set<MessageDestinationReferenceDescriptor> messageDestReferences =
            new HashSet<MessageDestinationReferenceDescriptor>();
    private Set<ResourceReferenceDescriptor> resourceReferences =
            new HashSet<ResourceReferenceDescriptor>();
    private Set<ServiceReferenceDescriptor> serviceReferences =
            new HashSet<ServiceReferenceDescriptor>();

    private Set<LifecycleCallbackDescriptor> postConstructDescs =
            new HashSet<LifecycleCallbackDescriptor>();
    private Set<LifecycleCallbackDescriptor> preDestroyDescs =
            new HashSet<LifecycleCallbackDescriptor>();
    private Set<LifecycleCallbackDescriptor> aroundInvokeDescs =
            new HashSet<LifecycleCallbackDescriptor>();

    private Set<EntityManagerFactoryReferenceDescriptor>
            entityManagerFactoryReferences =
            new HashSet<EntityManagerFactoryReferenceDescriptor>();

    private Set<EntityManagerReferenceDescriptor>
            entityManagerReferences =
            new HashSet<EntityManagerReferenceDescriptor>();

    private Set roleReferences = new HashSet();
    private EjbBundleDescriptor bundleDescriptor;
    // private EjbIORConfigurationDescriptor iorConfigDescriptor = new EjbIORConfigurationDescriptor();
    private Set iorConfigDescriptors = new OrderedSet();
    //private Set methodDescriptors = new HashSet();
    private String ejbClassName;

    // EjbRefs from all components in this app who point to me
    private Set ejbReferencersPointingToMe = new HashSet();

    // For EJB2.0
    protected Boolean usesCallerIdentity = null;
    protected String securityIdentityDescription;
    protected boolean isDistributedTxScope = true;
    protected RunAsIdentityDescriptor runAsIdentity = null;

    // sets of method descriptor that can be of style 1 or style 2
    // we initialize it so we force at least on method conversion
    // to fill up unspecified method with the unchecked permission
    private Map styledMethodDescriptors = new HashMap();

    private long uniqueId;
    private String remoteHomeImplClassName;
    private String ejbObjectImplClassName;
    private String localHomeImplClassName;
    private String ejbLocalObjectImplClassName;

    private MethodDescriptor timedObjectMethod;

    private List<ScheduledTimerDescriptor> timerSchedules =
            new ArrayList<ScheduledTimerDescriptor>();


    private ConcurrentMap<Method, MethodDescriptor> allMethodDescriptors = 
            new ConcurrentHashMap<Method, MethodDescriptor>();

    //
    // The set of all interceptor classes applicable to this bean.  This 
    // includes any interceptor class that is present at *either* the class
    // level or method-level.
    //
    private Set<EjbInterceptor> allInterceptorClasses =
            new HashSet<EjbInterceptor>();

    // Ordered list of class-level interceptors for this bean.  
    private List<EjbInterceptor> interceptorChain =
            new LinkedList<EjbInterceptor>();

    //
    // Interceptor info per business method.  If the map does not
    // contain an entry for the business method, there is no method-specific
    // interceptor information for that method.  In that case the standard
    // class-level interceptor information applies.  
    //
    // If there is an entry for the business method, the corresponding list 
    // represents the *complete* ordered list of interceptor classes for that 
    // method.  An empty list would mean all the interceptors have been
    // disabled for that particular business method.
    // 
    private Map<MethodDescriptor, List<EjbInterceptor>> methodInterceptorsMap =
            new HashMap<MethodDescriptor, List<EjbInterceptor>>();

    private static LocalStringManagerImpl localStrings =
            new LocalStringManagerImpl(EjbDescriptor.class);

    static Logger _logger = DOLUtils.getDefaultLogger();

    private IASEjbExtraDescriptors iASEjbExtraDescriptors = new IASEjbExtraDescriptors();  // Ludo 12/10/2001 extra DTD info only for iAS

    /**
     * returns the extra iAS specific info (not in the RI DID) in the iAS DTD.
     * no setter. You have to modify some fields of the returned object to change it.
     * TODO: check if we need to clone it in the Copy Constructor...
     */
    public IASEjbExtraDescriptors getIASEjbExtraDescriptors() {
        return iASEjbExtraDescriptors;
    }

    /**
     * Default constructor.
     */
    protected EjbDescriptor() {
    }

    public EjbDescriptor(EjbDescriptor other) {
        super(other);
        addEjbDescriptor(other);
    }

    public void addEjbDescriptor(EjbDescriptor other) {
        this.transactionType = other.transactionType;
        this.methodContainerTransactions = new Hashtable(other.getMethodContainerTransactions());
        this.permissionedMethodsByPermission = new Hashtable(other.getPermissionedMethodsByPermission());
        this.getEnvironmentProperties().addAll(other.getEnvironmentProperties());
        this.getEjbReferenceDescriptors().addAll(other.getEjbReferenceDescriptors());
        this.getJmsDestinationReferenceDescriptors().addAll(other.getJmsDestinationReferenceDescriptors());
        this.getMessageDestinationReferenceDescriptors().addAll(other.getMessageDestinationReferenceDescriptors());
        this.getResourceReferenceDescriptors().addAll(other.getResourceReferenceDescriptors());
        this.getServiceReferenceDescriptors().addAll(other.getServiceReferenceDescriptors());
        this.getRoleReferences().addAll(other.getRoleReferences());
        this.getIORConfigurationDescriptors().addAll(other.getIORConfigurationDescriptors());
        this.transactionType = other.transactionType;
        this.ejbClassName = other.ejbClassName;
        this.usesCallerIdentity = other.usesCallerIdentity;
        this.bundleDescriptor = other.bundleDescriptor;
        this.timerSchedules = new ArrayList(other.timerSchedules);
        this.allMethodDescriptors = new ConcurrentHashMap(other.allMethodDescriptors);
    }

    /**
     * Sets the classname of the ejb.
     */
    public void setEjbClassName(String ejbClassName) {
        this.ejbClassName = ejbClassName;
    }

    /**
     * Returns the classname of the ejb.
     */
    public String getEjbClassName() {
        return this.ejbClassName;
    }

    /**
     * IASRI 4725194
     * Returns the Execution class ,which is same as the user-specified class
     * in case of Message,Session and Bean Managed Persistence Entity Beans
     * but is different for Container Mananged Persistence Entity Bean
     * Therefore,the implementation in the base class is to return
     * getEjbClassName() and the method is redefined in IASEjbCMPDescriptor.
     */
    public String getEjbImplClassName() {
        return this.getEjbClassName();
    }

    /**
     * Sets the remote home implementation classname of the ejb.
     */
    public void setRemoteHomeImplClassName(String name) {
        this.remoteHomeImplClassName = name;
    }

    /**
     * Returns the classname of the remote home impl.
     */
    public String getRemoteHomeImplClassName() {
        return this.remoteHomeImplClassName;
    }

    /**
     * Sets the Local home implementation classname of the ejb.
     */
    public void setLocalHomeImplClassName(String name) {
        this.localHomeImplClassName = name;
    }

    /**
     * Returns the classname of the Local home impl.
     */
    public String getLocalHomeImplClassName() {
        return this.localHomeImplClassName;
    }


    /**
     * Sets the EJBLocalObject implementation classname of the ejb.
     */
    public void setEJBLocalObjectImplClassName(String name) {
        this.ejbLocalObjectImplClassName = name;
    }

    /**
     * Returns the classname of the EJBLocalObject impl.
     */
    public String getEJBLocalObjectImplClassName() {
        return this.ejbLocalObjectImplClassName;
    }


    /**
     * Sets the EJBObject implementation classname of the ejb.
     */
    public void setEJBObjectImplClassName(String name) {
        this.ejbObjectImplClassName = name;
    }

    /**
     * Returns the classname of the EJBObject impl.
     */
    public String getEJBObjectImplClassName() {
        return this.ejbObjectImplClassName;
    }

    /**
     * The transaction type of this ejb.
     */
    public String getTransactionType() {
        return this.transactionType;
    }

    /**
     * Set the transaction type of this ejb.
     */
    public abstract void setTransactionType(String transactionType);

    /**
     * Returns the set of transaction attributes that can be assigned
     * to methods of this ejb when in CMT mode. Elements are of type
     * ContainerTransaction
     */
    public Vector getPossibleTransactionAttributes() {
        Vector txAttributes = new Vector();
        txAttributes.add(new ContainerTransaction
                (ContainerTransaction.MANDATORY, ""));
        txAttributes.add(new ContainerTransaction
                (ContainerTransaction.NEVER, ""));
        txAttributes.add(new ContainerTransaction
                (ContainerTransaction.NOT_SUPPORTED, ""));
        txAttributes.add(new ContainerTransaction
                (ContainerTransaction.REQUIRED, ""));
        txAttributes.add(new ContainerTransaction
                (ContainerTransaction.REQUIRES_NEW, ""));
        txAttributes.add(new ContainerTransaction
                (ContainerTransaction.SUPPORTS, ""));
        return txAttributes;
    }

    public boolean isTimedObject() {
        return (timedObjectMethod != null || timerSchedules.size() > 0);
    }

    public MethodDescriptor getEjbTimeoutMethod() {
        return timedObjectMethod;
    }

    public void setEjbTimeoutMethod(MethodDescriptor method) {
        timedObjectMethod = method;
    }

    public void addScheduledTimerDescriptor(ScheduledTimerDescriptor scheduleDescriptor) {
        timerSchedules.add(scheduleDescriptor);
    }

    public List<ScheduledTimerDescriptor> getScheduledTimerDescriptors() {
        return timerSchedules;
    }

    public Set<LifecycleCallbackDescriptor>
    getPostConstructDescriptors() {
        return postConstructDescs;
    }

    public void addPostConstructDescriptor(LifecycleCallbackDescriptor
            postConstructDesc) {
        String className = postConstructDesc.getLifecycleCallbackClass();
        boolean found = false;
        for (LifecycleCallbackDescriptor next :
                getPostConstructDescriptors()) {
            if (next.getLifecycleCallbackClass().equals(className)) {
                found = true;
                break;
            }
        }
        if (!found) {
            getPostConstructDescriptors().add(postConstructDesc);
        }
    }

    public LifecycleCallbackDescriptor
    getPostConstructDescriptorByClass(String className) {
        return bundleDescriptor.getPostConstructDescriptorByClass
                (className, this);
    }

    public boolean hasPostConstructMethod() {
        return (getPostConstructDescriptors().size() > 0);
    }

    public Set<LifecycleCallbackDescriptor>
    getPreDestroyDescriptors() {
        return preDestroyDescs;
    }

    public void addPreDestroyDescriptor(LifecycleCallbackDescriptor
            preDestroyDesc) {
        String className = preDestroyDesc.getLifecycleCallbackClass();
        boolean found = false;
        for (LifecycleCallbackDescriptor next :
                getPreDestroyDescriptors()) {
            if (next.getLifecycleCallbackClass().equals(className)) {
                found = true;
                break;
            }
        }
        if (!found) {
            getPreDestroyDescriptors().add(preDestroyDesc);
        }
    }

    public LifecycleCallbackDescriptor
    getPreDestroyDescriptorByClass(String className) {
        return bundleDescriptor.getPreDestroyDescriptorByClass
                (className, this);
    }

    public boolean hasPreDestroyMethod() {
        return (getPreDestroyDescriptors().size() > 0);
    }

    public Set<LifecycleCallbackDescriptor> getAroundInvokeDescriptors() {
        return aroundInvokeDescs;
    }

    public void addAroundInvokeDescriptor(LifecycleCallbackDescriptor
            aroundInvokeDesc) {
        String className = aroundInvokeDesc.getLifecycleCallbackClass();
        boolean found = false;
        for (LifecycleCallbackDescriptor next :
                getAroundInvokeDescriptors()) {
            if (next.getLifecycleCallbackClass().equals(className)) {
                found = true;
                break;
            }
        }
        if (!found) {
            getAroundInvokeDescriptors().add(aroundInvokeDesc);
        }
    }

    public LifecycleCallbackDescriptor
    getAroundInvokeDescriptorByClass(String className) {

        for (LifecycleCallbackDescriptor next :
                getAroundInvokeDescriptors()) {
            if (next.getLifecycleCallbackClass().equals(className)) {
                return next;
            }
        }
        return null;
    }

    public boolean hasAroundInvokeMethod() {
        return (getAroundInvokeDescriptors().size() > 0);
    }

    /**
     * Since ejb-class is optional, in some cases the lifecycle-class
     * for AroundInvoke, PostConstruct, etc. methods on the bean-class
     * is not known at processing time and must be applied lazily.  As such,
     * this method should only be called if the ejb-class has been set
     * on this EjbDescriptor.
     */
    public void applyDefaultClassToLifecycleMethods() {
        Set<LifecycleCallbackDescriptor> lifecycleMethods =
                new HashSet<LifecycleCallbackDescriptor>();
        lifecycleMethods.addAll(getAroundInvokeDescriptors());
        lifecycleMethods.addAll(getPostConstructDescriptors());
        lifecycleMethods.addAll(getPreDestroyDescriptors());
        if (getType().equals(EjbSessionDescriptor.TYPE)) {
            EjbSessionDescriptor sfulDesc = (EjbSessionDescriptor) this;
            lifecycleMethods.addAll(sfulDesc.getPrePassivateDescriptors());
            lifecycleMethods.addAll(sfulDesc.getPostActivateDescriptors());
        }
        for (LifecycleCallbackDescriptor next : lifecycleMethods) {
            if (next.getLifecycleCallbackClass() == null) {
                next.setLifecycleCallbackClass(getEjbClassName());
            }
        }
    }

    /**
     * Derive all interceptors that are applicable to this bean.
     */
    public void applyInterceptors(InterceptorBindingTranslator
            bindingTranslator) {

        // Apply this ejb to the ordered set of all interceptor bindings
        // for this ejb-jar.  The results will contain all interceptor
        // information that applies to the ejb.  There is no notion of
        // default interceptors within the results.  Default interceptors
        // are used during the translation process but once we derive
        // the per-ejb interceptor information there is only a notion of
        // class-level ordering and method-level ordering.  Any applicable
        // default interceptors will have been applied to the class-level.
        TranslationResults results = bindingTranslator.apply(getName());

        allInterceptorClasses.clear();
        allInterceptorClasses.addAll(results.allInterceptorClasses);

        interceptorChain.clear();
        interceptorChain.addAll(results.classInterceptorChain);

        methodInterceptorsMap.clear();
        methodInterceptorsMap.putAll(results.methodInterceptorsMap);

        for (EjbInterceptor interceptor : allInterceptorClasses) {
            for (Object ejbRefObj : interceptor.getEjbReferenceDescriptors()) {
                addEjbReferenceDescriptor((EjbReference) ejbRefObj);
            }

            for (Object msgDestRefObj :
                    interceptor.getMessageDestinationReferenceDescriptors()) {
                addMessageDestinationReferenceDescriptor(
                        (MessageDestinationReferenceDescriptor) msgDestRefObj);
            }

            for (Object envPropObj : interceptor.getEnvironmentProperties()) {
                addEnvironmentProperty((EnvironmentProperty) envPropObj);
            }

            for (Object servRefObj :
                    interceptor.getServiceReferenceDescriptors()) {
                addServiceReferenceDescriptor(
                        (ServiceReferenceDescriptor) servRefObj);
            }

            for (Object resRefObj :
                    interceptor.getResourceReferenceDescriptors()) {
                addResourceReferenceDescriptor(
                        (ResourceReferenceDescriptor) resRefObj);
            }

            for (Object jmsDestRefObj :
                    interceptor.getJmsDestinationReferenceDescriptors()) {
                addJmsDestinationReferenceDescriptor(
                        (JmsDestinationReferenceDescriptor) jmsDestRefObj);
            }

            for (EntityManagerFactoryReferenceDescriptor entMgrFacRef :
                    interceptor.getEntityManagerFactoryReferenceDescriptors()) {
                addEntityManagerFactoryReferenceDescriptor(entMgrFacRef);
            }

            for (EntityManagerReferenceDescriptor entMgrRef :
                    interceptor.getEntityManagerReferenceDescriptors()) {
                addEntityManagerReferenceDescriptor(entMgrRef);
            }
        }
    }

    /**
     * Return an unordered set of interceptor descriptors for this bean.
     * This list does not include interceptor info for the bean
     * class itself, even if the bean class declares AroundInvoke methods
     * and/or callbacks.
     */
    public Set<EjbInterceptor> getInterceptorClasses() {
        return new HashSet<EjbInterceptor>(allInterceptorClasses);
    }

    /**
     * Return an unordered set of the names of all interceptor classes
     * for this bean.  This list does not include the name of the bean
     * class itself, even if the bean class declares AroundInvoke methods
     * and/or callbacks.
     */
    public Set<String> getInterceptorClassNames() {

        HashSet<String> classNames = new HashSet<String>();

        for (EjbInterceptor ei : getInterceptorClasses()) {
            classNames.add(ei.getInterceptorClassName());
        }

        return classNames;
    }

    public Map<MethodDescriptor, List<EjbInterceptor>>
    getMethodInterceptorsMap() {
        return new HashMap<MethodDescriptor, List<EjbInterceptor>>
                (methodInterceptorsMap);
    }

    public List<EjbInterceptor> getInterceptorChain() {
        return new LinkedList<EjbInterceptor>(interceptorChain);
    }

    /**
     * Return the ordered list of interceptor info for AroundInvoke behavior
     * of a particular business method.  This list *does* include the info
     * on any bean class interceptor.  If present, this would always be the
     * last element in the list because of the precedence defined by the spec.
     */
    public List<EjbInterceptor> getAroundInvokeInterceptors
            (MethodDescriptor businessMethod) {

        LinkedList<EjbInterceptor> aroundInvokeInterceptors =
                new LinkedList<EjbInterceptor>();

        List<EjbInterceptor> classOrMethodInterceptors = null;

        for (MethodDescriptor methodDesc : methodInterceptorsMap.keySet()) {
            if (methodDesc.implies(businessMethod)) {
                classOrMethodInterceptors =
                        methodInterceptorsMap.get(methodDesc);
            }
        }

        if( classOrMethodInterceptors == null ) {
            classOrMethodInterceptors = interceptorChain;
        }

        for (EjbInterceptor next : classOrMethodInterceptors) {
            if (next.getAroundInvokeDescriptors().size() > 0) {
                aroundInvokeInterceptors.add(next);
            }
        }

        if (hasAroundInvokeMethod()) {

            EjbInterceptor interceptorInfo = new EjbInterceptor();
            interceptorInfo.setFromBeanClass(true);
            interceptorInfo.addAroundInvokeDescriptors(getAroundInvokeDescriptors());
            interceptorInfo.setInterceptorClassName(getEjbImplClassName());

            aroundInvokeInterceptors.add(interceptorInfo);
        }

        return aroundInvokeInterceptors;
    }

    /**
     * Return the ordered list of interceptor info for a particular
     * callback event type.  This list *does* include the info
     * on any bean class callback.  If present, this would always be the
     * last element in the list because of the precedence defined by the spec.
     */
    public List<EjbInterceptor> getCallbackInterceptors(CallbackType type) {

        LinkedList<EjbInterceptor> callbackInterceptors =
                new LinkedList<EjbInterceptor>();

        for (EjbInterceptor next : interceptorChain) {
            if (next.getCallbackDescriptors(type).size() > 0) {
                callbackInterceptors.add(next);
            }
        }

        EjbInterceptor beanClassCallbackInfo = null;

        switch (type) {
            case POST_CONSTRUCT:

                if (hasPostConstructMethod()) {
                    beanClassCallbackInfo = new EjbInterceptor();
                    beanClassCallbackInfo.setFromBeanClass(true);
                    beanClassCallbackInfo.addCallbackDescriptors
                            (type, getPostConstructDescriptors());
                }
                break;

            case PRE_DESTROY:

                if (hasPreDestroyMethod()) {
                    beanClassCallbackInfo = new EjbInterceptor();
                    beanClassCallbackInfo.setFromBeanClass(true);
                    beanClassCallbackInfo.addCallbackDescriptors
                            (type, getPreDestroyDescriptors());
                }
                break;

            case PRE_PASSIVATE:

                if (((EjbSessionDescriptor) this).hasPrePassivateMethod()) {
                    beanClassCallbackInfo = new EjbInterceptor();
                    beanClassCallbackInfo.setFromBeanClass(true);
                    beanClassCallbackInfo.addCallbackDescriptors(type,
                            ((EjbSessionDescriptor) this).getPrePassivateDescriptors());
                }

                break;

            case POST_ACTIVATE:

                if (((EjbSessionDescriptor) this).hasPostActivateMethod()) {
                    beanClassCallbackInfo = new EjbInterceptor();
                    beanClassCallbackInfo.setFromBeanClass(true);
                    beanClassCallbackInfo.addCallbackDescriptors(type,
                            ((EjbSessionDescriptor) this).getPostActivateDescriptors());
                }

                break;

        }

        if (beanClassCallbackInfo != null) {

            beanClassCallbackInfo.setInterceptorClassName
                    (getEjbImplClassName());
            callbackInterceptors.add(beanClassCallbackInfo);

        }

        return callbackInterceptors;
    }


    /**
     * Gets the transaction scope of this ejb.
     *
     * @return true if bean has distributed tx scope (default).
     */
    public boolean isDistributedTransactionScope() {
        return isDistributedTxScope;
    }

    /**
     * Set the transaction scope of this ejb.
     */
    public void setDistributedTransactionScope(boolean scope) {
        isDistributedTxScope = scope;
    }


    /**
     * Set the usesCallerIdentity flag
     */
    public void setUsesCallerIdentity(boolean flag) {
        usesCallerIdentity = flag;
    }

    /**
     * Get the usesCallerIdentity flag
     *
     * @return Boolean.TRUE if this bean uses caller identity
     *         null if this is called before validator visit
     */
    public Boolean getUsesCallerIdentity() {
        return usesCallerIdentity;
    }


    /**
     * Get the description field of security-identity
     */
    public String getSecurityIdentityDescription() {
        if (securityIdentityDescription == null)
            securityIdentityDescription = "";
        return securityIdentityDescription;
    }

    /**
     * Set the description field of security-identity
     */
    public void setSecurityIdentityDescription(String s) {
        securityIdentityDescription = s;
    }


    public void setRunAsIdentity(RunAsIdentityDescriptor desc) {
        if (usesCallerIdentity == null || usesCallerIdentity)
            throw new IllegalStateException(localStrings.getLocalString(
                    "exceptioncannotsetrunas",
                    "Cannot set RunAs identity when using caller identity"));
        this.runAsIdentity = desc;
    }

    public RunAsIdentityDescriptor getRunAsIdentity() {
        if (usesCallerIdentity == null || usesCallerIdentity)
            throw new IllegalStateException(localStrings.getLocalString(
                    "exceptioncannotgetrunas",
                    "Cannot get RunAs identity when using caller identity"));
        return runAsIdentity;
    }

    /**
     * Have default method transaction if isBoundsChecking is on.
     */
    public void setUsesDefaultTransaction() {
        usesDefaultTransaction = true;
    }

    /**
     * @return a state to indicate whether default method transaction is used
     *         if isBoundsChecking is on.
     */
    public boolean isUsesDefaultTransaction() {
        return usesDefaultTransaction;
    }

    /**
     * Return a copy of the mapping held internally of method descriptors to container transaction objects.
     */
    public Hashtable getMethodContainerTransactions() {
        if (this.methodContainerTransactions == null) {
            this.methodContainerTransactions = new Hashtable();
        }
        return methodContainerTransactions;
    }

    /**
     * Sets the container transaction for the given method descriptor.
     * Throws an Illegal argument if this ejb has transaction type BEAN_TRANSACTION_TYPE.
     */
    public void setContainerTransactionFor(MethodDescriptor methodDescriptor, ContainerTransaction containerTransaction) {
        ContainerTransaction oldValue = this.getContainerTransactionFor(methodDescriptor);
        if (oldValue == null || (oldValue != null && !(oldValue.equals(containerTransaction)))) {
            String transactionType = this.getTransactionType();
            if (transactionType == null) {
                setTransactionType(CONTAINER_TRANSACTION_TYPE);
                transactionType = CONTAINER_TRANSACTION_TYPE;
            } else if (BEAN_TRANSACTION_TYPE.equals(transactionType)) {
                throw new IllegalArgumentException(localStrings.getLocalString(
                        "enterprise.deployment.exceptiontxattrbtnotspecifiedinbeanwithtxtype",
                        "Method level transaction attributes may not be specified on a bean with transaction type {0}", new Object[]{EjbSessionDescriptor.BEAN_TRANSACTION_TYPE}));
            }
            //_logger.log(Level.FINE,"put " + methodDescriptor + " " + containerTransaction);
            getMethodContainerTransactions().put(methodDescriptor, containerTransaction);
        }
    }

    private void removeContainerTransactionFor(MethodDescriptor methodDescriptor) {
        getMethodContainerTransactions().remove(methodDescriptor);
    }

    /**
     * Sets the container transactions for all the method descriptors of this ejb. The Hashtable is keyed
     * by method descriptor and the values are the corresponding container transaction objects..
     * Throws an Illegal argument if this ejb has transaction type BEAN_TRANSACTION_TYPE.
     */
    public void setMethodContainerTransactions(Hashtable methodContainerTransactions) {
        if (methodContainerTransactions == null || methodContainerTransactions.isEmpty()) {
            methodContainerTransactions = null;
        } else {
            for (Enumeration e = methodContainerTransactions.keys(); e.hasMoreElements();) {
                MethodDescriptor methodDescriptor = (MethodDescriptor) e.nextElement();
                ContainerTransaction containerTransaction =
                        (ContainerTransaction) methodContainerTransactions.get(methodDescriptor);
                setContainerTransactionFor(methodDescriptor, containerTransaction);
            }
        }
    }

    Set getAllMethodDescriptors() {
        Set allMethodDescriptors = new HashSet();
        for (Enumeration e = getMethodContainerTransactions().keys(); e.hasMoreElements();) {
            allMethodDescriptors.add(e.nextElement());
        }
        for (Iterator e = this.getPermissionedMethodsByPermission().keySet().iterator(); e.hasNext();) {
            MethodPermission nextPermission = (MethodPermission) e.next();
            Set permissionedMethods = (Set) this.getPermissionedMethodsByPermission().get(nextPermission);
            for (Iterator itr = permissionedMethods.iterator(); itr.hasNext();) {
                allMethodDescriptors.add(itr.next());
            }
        }
        return allMethodDescriptors;
    }


    /**
     * Fetches the assigned container transaction object for the given method object or null.
     */
    public ContainerTransaction getContainerTransactionFor(MethodDescriptor methodDescriptor) {
        ContainerTransaction containerTransaction = null;
        if (this.needToConvertMethodContainerTransactions()) {
            this.convertMethodContainerTransactions();
        }
        containerTransaction = (ContainerTransaction) this.getMethodContainerTransactions().get(methodDescriptor);
        if (containerTransaction == null) {
            if (this.isBoundsChecking() && usesDefaultTransaction) {
                containerTransaction = new ContainerTransaction(ContainerTransaction.REQUIRED, "");
                this.getMethodContainerTransactions().put(methodDescriptor, containerTransaction);
            } else {
                containerTransaction = null;
            }
        }
        return containerTransaction;
    }

    private boolean needToConvertMethodContainerTransactions() {
        if (this.getEjbBundleDescriptor() != null) {
            for (Enumeration e = this.getMethodContainerTransactions().keys(); e.hasMoreElements();) {
                MethodDescriptor md = (MethodDescriptor) e.nextElement();
                if (!md.isExact()) {
                    return true;
                }
            }
        }
        return false;
    }


    private void convertMethodContainerTransactions() {
        // container transactions first
        //Hashtable transactions = this.getMethodContainerTransactions();
        //_logger.log(Level.FINE,"Pre conversion = " + transactions);
        Hashtable convertedTransactions = new Hashtable();
        convertMethodContainerTransactionsOfStyle(1, convertedTransactions);
        convertMethodContainerTransactionsOfStyle(2, convertedTransactions);
        convertMethodContainerTransactionsOfStyle(3, convertedTransactions);
        //_logger.log(Level.FINE,"Post conversion = " + convertedTransactions);
        this.methodContainerTransactions = convertedTransactions;
    }

    private void convertMethodContainerTransactionsOfStyle(int requestedStyleForConversion, Hashtable convertedMethods) {

        Collection transactionMethods = this.getTransactionMethodDescriptors();
        Hashtable transactions = this.getMethodContainerTransactions();
        for (Enumeration e = transactions.keys(); e.hasMoreElements();) {
            MethodDescriptor md = (MethodDescriptor) e.nextElement();
            if (md.getStyle() == requestedStyleForConversion) {
                ContainerTransaction ct = (ContainerTransaction) getMethodContainerTransactions().get(md);
                for (Enumeration mds = md.doStyleConversion(this, transactionMethods).elements(); mds.hasMoreElements();)
                {
                    MethodDescriptor next = (MethodDescriptor) mds.nextElement();
                    convertedMethods.put(next, new ContainerTransaction(ct));
                }
            }
        }
    }

    /**
     * returns a ContainerTransaction if all the transactional methods on
     * the ejb descriptor have the same transaction type else return null
     */
    public ContainerTransaction getContainerTransaction() {
        Vector transactionalMethods = new Vector(this.getTransactionMethodDescriptors());
        MethodDescriptor md = (MethodDescriptor) transactionalMethods.firstElement();
        if (md != null) {
            ContainerTransaction first = this.getContainerTransactionFor(md);
            for (Enumeration e = transactionalMethods.elements(); e.hasMoreElements();) {
                MethodDescriptor next = (MethodDescriptor) e.nextElement();
                ContainerTransaction nextCt = this.getContainerTransactionFor(next);
                if (nextCt != null && !nextCt.equals(first)) {
                    return null;
                }
            }
            return first;
        }
        return null;
    }

    public Set getIORConfigurationDescriptors() {
        return iorConfigDescriptors;
    }

    public void addIORConfigurationDescriptor(EjbIORConfigurationDescriptor val) {
        iorConfigDescriptors.add(val);

    }

    /**
     * @eturn the set of roles to which have been assigned method permissions.
     */
    public Set getPermissionedRoles() {
        if (needToConvertMethodPermissions()) {
            convertMethodPermissions();
        }
        Set allPermissionedRoles = new HashSet();
        for (Iterator i = this.getPermissionedMethodsByPermission().keySet().iterator(); i.hasNext();) {
            MethodPermission pm = (MethodPermission) i.next();
            if (pm.isRoleBased()) {
                allPermissionedRoles.add(pm.getRole());
            }
        }
        return allPermissionedRoles;
    }

    /**
     * @return the Map of MethodPermission (keys) that have been assigned to
     *         MethodDescriptors (elements)
     */
    public Map getPermissionedMethodsByPermission() {
        if (permissionedMethodsByPermission == null) {
            permissionedMethodsByPermission = new Hashtable();
        }
        return permissionedMethodsByPermission;
    }

    /**
     * Add a new method permission to a method or a set of methods
     *
     * @param mp is the new method permission to assign
     * @param md describe the method or set of methods this permission apply to
     */
    public void addPermissionedMethod(MethodPermission mp, MethodDescriptor md) {
        if (getEjbBundleDescriptor() == null) {
            throw new IllegalArgumentException(localStrings.getLocalString(
                    "enterprise.deployment.exceptioncannotaddrolesdescriptor",
                    "Cannot add roles when the descriptor is not part of a bundle"));
        }
        if (mp.isRoleBased()) {
            if (!getEjbBundleDescriptor().getRoles().contains(mp.getRole())) {
                throw new IllegalArgumentException(localStrings.getLocalString(
                        "enterprise.deployment.exceptioncannotaddrolesbundle",
                        "Cannot add roles when the bundle does not have them"));
            }
        }

        if (md.isExact()) {
            updateMethodPermissionForMethod(mp, md);
        } else {
            addMethodPermissionForStyledMethodDescriptor(mp, md);
        }

        saveMethodPermissionFromDD(mp, md);
    }

    /**
     * Keep a record of all the Method Permissions exactly as they were in the DD
     */
    private void saveMethodPermissionFromDD(MethodPermission mp,
                                            MethodDescriptor md) {

        if (methodPermissionsFromDD == null) {
            methodPermissionsFromDD = new HashMap();
        }

        // we organize by permission, makes it easier...
        // Use Array List  as apposed to HashMap or Table because MethodDescriptor
        // Equality once did not take into account differences in
        // method interface, and will process sequentially.
        ArrayList descriptors = (ArrayList) methodPermissionsFromDD.get(mp);
        if (descriptors == null)
            descriptors = new ArrayList();
        descriptors.add(md);
        methodPermissionsFromDD.put(mp, descriptors);
    }

    /**
     * Get a record of all the Method Permissions exactly as they were in the`DD
     */
    public HashMap getMethodPermissionsFromDD() {
        return methodPermissionsFromDD;
    }

    private void addMethodPermissionForMethod(MethodPermission mp, MethodDescriptor md) {

        if (getPermissionedMethodsByPermission().containsKey(mp)) {
            Set alreadyPermissionedMethodsForThisRole = (Set) getPermissionedMethodsByPermission().get(mp);
            alreadyPermissionedMethodsForThisRole.add(md);
            this.getPermissionedMethodsByPermission().put(mp, alreadyPermissionedMethodsForThisRole);
        } else {
            Set permissionedMethodsForThisRole = new HashSet();
            permissionedMethodsForThisRole.add(md);
            this.getPermissionedMethodsByPermission().put(mp, permissionedMethodsForThisRole);
        }

    }

    /**
     * Remove a method permission from a method or a set of methods
     *
     * @param mp is the method permission to remove
     * @param md describe the method or set of methods this permission apply to
     */
    public void removePermissionedMethod(MethodPermission mp, MethodDescriptor md) {
        if (this.getEjbBundleDescriptor() == null) {
            throw new IllegalArgumentException(localStrings.getLocalString(
                    "enterprise.deployment.exceptioncanotaddrolesdescriptor",
                    "Cannot add roles when the descriptor is not part of a bundle"));
        }
        if (mp.isRoleBased()) {
            if (!getEjbBundleDescriptor().getRoles().contains(mp.getRole())) {
                throw new IllegalArgumentException(localStrings.getLocalString(
                        "enterprise.deployment.exceptioncannotaddrolesbndledoesnothave",
                        "Cannot add roles when the bundle does not have them"));
            }
        }

        if (this.getPermissionedMethodsByPermission().containsKey(mp)) {
            Set alreadyPermissionedMethodsForThisRole = (Set) this.getPermissionedMethodsByPermission().get(mp);
            alreadyPermissionedMethodsForThisRole.remove(md);
            this.getPermissionedMethodsByPermission().put(mp, alreadyPermissionedMethodsForThisRole);
        }

    }

    /**
     * add a style 1 or 2 in our tables
     */
    private void addMethodPermissionForStyledMethodDescriptor(MethodPermission mp, MethodDescriptor md) {

        if (styledMethodDescriptors == null) {
            styledMethodDescriptors = new HashMap();
        }

        // we organize per method descriptors, makes it easier...
        Set permissions = (Set) styledMethodDescriptors.get(md);
        if (permissions == null)
            permissions = new HashSet();
        permissions.add(mp);
        styledMethodDescriptors.put(md, permissions);
    }

    /**
     * @return a map of permission to style 1 or 2 method descriptors
     */
    public Map getStyledPermissionedMethodsByPermission() {
        if (styledMethodDescriptors == null) {
            return null;
        }

        // the current info is structured as MethodDescriptors as keys to
        // method permission, let's reverse this to make the Map using the 
        // method permission as a key.
        Map styledMethodDescriptorsByPermission = new HashMap();
        for (Iterator mdIterator = styledMethodDescriptors.keySet().iterator(); mdIterator.hasNext();) {
            MethodDescriptor md = (MethodDescriptor) mdIterator.next();
            Set methodPermissions = (Set) styledMethodDescriptors.get(md);
            for (Iterator mpIterator = methodPermissions.iterator(); mpIterator.hasNext();) {
                MethodPermission mp = (MethodPermission) mpIterator.next();

                Set methodDescriptors = (Set) styledMethodDescriptorsByPermission.get(mp);
                if (methodDescriptors == null) {
                    methodDescriptors = new HashSet();
                }
                methodDescriptors.add(md);
                styledMethodDescriptorsByPermission.put(mp, methodDescriptors);
            }
        }
        return styledMethodDescriptorsByPermission;
    }

    /**
     * @return a Set of method descriptors for all the methods associated
     *         with an unchecked method permission
     */
    public Set getUncheckedMethodDescriptors() {
        if (needToConvertMethodPermissions()) {
            convertMethodPermissions();
        }
        return (Set) getPermissionedMethodsByPermission().get(MethodPermission.getUncheckedMethodPermission());
    }

    /**
     * @return a Set of method descriptors for all the methoda assoicated
     *         with an excluded method permission
     */
    public Set getExcludedMethodDescriptors() {
        if (needToConvertMethodPermissions()) {
            convertMethodPermissions();
        }
        return (Set) getPermissionedMethodsByPermission().get(MethodPermission.getExcludedMethodPermission());
    }

    /**
     * convert all style 1 and style 2 method descriptors contained in
     * our tables into style 3 method descriptors.
     */
    private void convertMethodPermissions() {

        if (styledMethodDescriptors == null)
            return;

        Set allMethods = getMethodDescriptors();
        Set unpermissionedMethods = getMethodDescriptors();

        Set methodDescriptors = styledMethodDescriptors.keySet();
        for (Iterator styledMdItr = methodDescriptors.iterator(); styledMdItr.hasNext();) {
            MethodDescriptor styledMd = (MethodDescriptor) styledMdItr.next();

            // Get the new permissions we are trying to set for this
            // method(s)
            Set newPermissions = (Set) styledMethodDescriptors.get(styledMd);

            // Convert to style 3 method descriptors
            Vector mds = styledMd.doStyleConversion(this, allMethods);
            for (Iterator mdItr = mds.iterator(); mdItr.hasNext();) {
                MethodDescriptor md = (MethodDescriptor) mdItr.next();

                // remove it from the list of unpermissioned methods.
                // it will be used at the end to set all remaining methods 
                // with the unchecked method permission
                unpermissionedMethods.remove(md);

                // iterator over the new set of method permissions for that
                // method descriptor and update the table
                for (Iterator newPermissionsItr = newPermissions.iterator(); newPermissionsItr.hasNext();) {
                    MethodPermission newMp = (MethodPermission) newPermissionsItr.next();
                    updateMethodPermissionForMethod(newMp, md);
                }
            }
        }

        // All remaining methods should now be defined as unchecked...        
        MethodPermission mp = MethodPermission.getUncheckedMethodPermission();
        Iterator iterator = unpermissionedMethods.iterator();
        while (iterator.hasNext()) {
            MethodDescriptor md = (MethodDescriptor) iterator.next();
            if (getMethodPermissions(md).isEmpty()) {
                addMethodPermissionForMethod(mp, md);
            }
        }

        // finally we reset the list of method descriptors that need style conversion
        styledMethodDescriptors = null;
    }

    private void dumpMethodPermissions() {
        _logger.log(Level.FINE, "For Bean " + getName());
        Map allPermissions = getPermissionedMethodsByPermission();
        Set permissions = allPermissions.keySet();
        for (Iterator permissionsIterator = permissions.iterator(); permissionsIterator.hasNext();) {
            MethodPermission mp = (MethodPermission) permissionsIterator.next();
            _logger.log(Level.FINE, " Method Permission : " + mp);
            Set allMethods = (Set) getPermissionedMethodsByPermission().get(mp);
            for (Iterator methodIterator = allMethods.iterator(); methodIterator.hasNext();) {
                MethodDescriptor md = (MethodDescriptor) methodIterator.next();
                _logger.log(Level.FINE, " -> " + md);
            }
        }
    }

    /**
     * Update a method descriptor set of method permission with a new method permission
     * The new method permission is added to the list of existing method permissions
     * given it respect the EJB 2.0 paragraph 21.3.2 on priorities of method permissions
     *
     * @param mp is the method permission to be added
     * @param md is the method descriptor (style3 only) to add the method permission to
     */
    private void updateMethodPermissionForMethod(MethodPermission mp, MethodDescriptor md) {

        // Get the current set of method permissions for that method
        Set oldPermissions = getMethodPermissions(md);

        if (oldPermissions.isEmpty()) {
            // this is easy, just add the new one
            addMethodPermissionForMethod(mp, md);
            return;
        }

        // The order of method permssion setting is very important
        // EJB 2.0 Spec 21.3.2
        // excluded method permission is always used when multiple methos permission are present
        // unchecked is considered like a role based method permission and is added to the list
        // therefore making the method callable by anyone.

        if (mp.isExcluded()) {
            // Excluded methods takes precedence on any other form of method permission
            // remove all existing method permission...
            for (Iterator oldPermissionsItr = oldPermissions.iterator(); oldPermissionsItr.hasNext();) {
                MethodPermission oldMp = (MethodPermission) oldPermissionsItr.next();
                removePermissionedMethod(oldMp, md);
            }
            // add the excluded
            addMethodPermissionForMethod(mp, md);
        } else {
            if (mp.isUnchecked()) {
                // we are trying to add an unchecked method permisison, all role-based
                // method permission should be removed since unchecked is now used, if a
                // particular method has an excluded method permision, we do not add it
                for (Iterator oldPermissionsItr = oldPermissions.iterator(); oldPermissionsItr.hasNext();) {
                    MethodPermission oldMp = (MethodPermission) oldPermissionsItr.next();
                    if (!oldMp.isExcluded()) {
                        removePermissionedMethod(oldMp, md);
                        addMethodPermissionForMethod(mp, md);
                    }
                }
            } else {
                // we are trying to add a role based method permission. Check that
                // unchecked or excluded method permissions have not been set
                // and add it to the current list of role based permission
                for (Iterator oldPermissionsItr = oldPermissions.iterator(); oldPermissionsItr.hasNext();) {
                    MethodPermission oldMp = (MethodPermission) oldPermissionsItr.next();
                    if (!oldMp.isExcluded()) {
                        if (!oldMp.isUnchecked()) {
                            addMethodPermissionForMethod(mp, md);
                        }
                    }
                }
            }
        }
    }

    /**
     * @return true if we have unconverted style 1 or style 2 method descriptors
     */
    private boolean needToConvertMethodPermissions() {
        return styledMethodDescriptors != null;
    }

    /**
     * @return the set of method permission assigned to a ejb method descriptor.
     */
    public Set getMethodPermissionsFor(MethodDescriptor methodDescriptor) {

        if (needToConvertMethodPermissions()) {
            convertMethodPermissions();
        }
        return getMethodPermissions(methodDescriptor);
    }

    private Set getMethodPermissions(MethodDescriptor methodDescriptor) {

        Set methodPermissionsForMethod = new HashSet();
        for (Iterator e = this.getPermissionedMethodsByPermission().keySet().iterator(); e.hasNext();) {
            MethodPermission nextPermission = (MethodPermission) e.next();
            Set permissionedMethods = (Set) this.getPermissionedMethodsByPermission().get(nextPermission);
            for (Iterator itr = permissionedMethods.iterator(); itr.hasNext();) {
                MethodDescriptor md = (MethodDescriptor) itr.next();
                if (md.equals(methodDescriptor)) {
                    methodPermissionsForMethod.add(nextPermission);
                }
            }
        }
        return methodPermissionsForMethod;
    }


    /**
     * Return the set of ejb references this ejb declares.
     */
    public Set<EjbReference> getEjbReferenceDescriptors() {
        return ejbReferences;
    }

    /**
     * Adds a reference to another ejb to me.
     */

    public void addEjbReferenceDescriptor(EjbReference ejbReference) {
        ejbReferences.add(ejbReference);
        ejbReference.setReferringBundleDescriptor(getEjbBundleDescriptor());
    }

    public void removeEjbReferenceDescriptor(EjbReference ejbReference) {
        ejbReferences.remove(ejbReference);
        ejbReference.setReferringBundleDescriptor(null);
    }

    public Set<ServiceReferenceDescriptor> getServiceReferenceDescriptors() {
        return serviceReferences;
    }

    public void addServiceReferenceDescriptor(ServiceReferenceDescriptor
            serviceRef) {
        serviceRef.setBundleDescriptor(getEjbBundleDescriptor());
        serviceReferences.add(serviceRef);
    }

    public void removeServiceReferenceDescriptor(ServiceReferenceDescriptor
            serviceRef) {
        serviceReferences.remove(serviceRef);
    }

    /**
     * Looks up an service reference with the given name.
     * Throws an IllegalArgumentException if it is not found.
     */
    public ServiceReferenceDescriptor getServiceReferenceByName(String name) {
        for (Iterator itr = this.getServiceReferenceDescriptors().iterator();
             itr.hasNext();) {
            ServiceReferenceDescriptor srd = (ServiceReferenceDescriptor)
                    itr.next();
            if (srd.getName().equals(name)) {
                return srd;
            }
        }
        throw new IllegalArgumentException(localStrings.getLocalString(
                "enterprise.deployment.exceptionejbhasnoservicerefbyname",
                "This ejb [{0}] has no service reference by the name of [{1}]",
                new Object[]{getName(), name}));
    }

    public Set<MessageDestinationReferenceDescriptor> getMessageDestinationReferenceDescriptors() {
        return messageDestReferences;
    }

    public void addMessageDestinationReferenceDescriptor
            (MessageDestinationReferenceDescriptor messageDestRef) {
        if (getEjbBundleDescriptor() != null) {
            messageDestRef.setReferringBundleDescriptor
                    (getEjbBundleDescriptor());
        }
        messageDestReferences.add(messageDestRef);
    }

    public void removeMessageDestinationReferenceDescriptor
            (MessageDestinationReferenceDescriptor msgDestRef) {
        messageDestReferences.remove(msgDestRef);
    }

    /**
     * Looks up an message destination reference with the given name.
     * Throws an IllegalArgumentException if it is not found.
     */
    public MessageDestinationReferenceDescriptor
    getMessageDestinationReferenceByName(String name) {

        for (MessageDestinationReferenceDescriptor mdr : messageDestReferences) {
            if (mdr.getName().equals(name)) {
                return mdr;
            }
        }
        throw new IllegalArgumentException(localStrings.getLocalString(
                "exceptionejbhasnomsgdestrefbyname",
                "This ejb [{0}] has no message destination reference by the name of [{1}]",
                new Object[]{getName(), name}));
    }

    /**
     * Return the set of JMS destination references this ejb declares.
     */
    public Set<JmsDestinationReferenceDescriptor> getJmsDestinationReferenceDescriptors() {
        return jmsDestReferences;
    }

    public void addJmsDestinationReferenceDescriptor(JmsDestinationReferenceDescriptor jmsDestReference) {
        jmsDestReferences.add(jmsDestReference);
    }

    public void removeJmsDestinationReferenceDescriptor(JmsDestinationReferenceDescriptor jmsDestReference) {
        jmsDestReferences.remove(jmsDestReference);
    }

    /**
     * Return the set of resource references this ejb declares.
     */
    public Set<ResourceReferenceDescriptor> getResourceReferenceDescriptors() {
        return resourceReferences;
    }

    /**
     * Adds a resource reference to me.
     */
    public void addResourceReferenceDescriptor(ResourceReferenceDescriptor resourceReference) {
        resourceReferences.add(resourceReference);
    }

    /**
     * Removes the given resource reference from me.
     */
    public void removeResourceReferenceDescriptor(ResourceReferenceDescriptor resourceReference) {
        resourceReferences.remove(resourceReference);
    }


    /**
     * Return the set of resource references this ejb declares that have been resolved..
     */
    public Set<ResourceReferenceDescriptor> getResourceReferenceDescriptors(boolean resolved) {
        Set<ResourceReferenceDescriptor> toReturn = new HashSet<ResourceReferenceDescriptor>();
        for (Iterator itr = this.getResourceReferenceDescriptors().iterator(); itr.hasNext();) {
            ResourceReferenceDescriptor next = (ResourceReferenceDescriptor) itr.next();
            if (next.isResolved() == resolved) {
                toReturn.add(next);
            }
        }
        return toReturn;
    }

    /**
     * Returns the environment property object searching on the supplied key.
     * throws an illegal argument exception if no such environment property exists.
     */
    public EnvironmentProperty getEnvironmentPropertyByName(String name) {
        for (Iterator itr = this.getEnvironmentProperties().iterator(); itr.hasNext();) {
            EnvironmentProperty ev = (EnvironmentProperty) itr.next();
            if (ev.getName().equals(name)) {
                return ev;
            }
        }
        throw new IllegalArgumentException(localStrings.getLocalString(
                "enterprise.deployment.exceptionbeanhasnoenvpropertybyname",
                "This bean {0} has no environment property by the name of {1}",
                new Object[]{getName(), name}));
    }

    /**
     * Return a reference to another ejb by the same name or throw an IllegalArgumentException.
     */
    public EjbReference getEjbReference(String name) {
        for (Iterator itr = this.getEjbReferenceDescriptors().iterator(); itr.hasNext();) {
            EjbReference er = (EjbReference) itr.next();
            if (er.getName().equals(name)) {
                return er;
            }
        }
        throw new IllegalArgumentException(localStrings.getLocalString(
                "enterprise.deployment.exceptionbeanhasnoejbrefbyname",
                "This bean {0} has no ejb reference by the name of {1}",
                new Object[]{getName(), name}));
    }


    /**
     * Return a reference to another ejb by the same name or throw an IllegalArgumentException.
     */
    public EjbReferenceDescriptor getEjbReferenceByName(String name) {
        return (EjbReferenceDescriptor) getEjbReference(name);
    }

    /**
     * Return a reference to another ejb by the same name or throw an IllegalArgumentException.
     */
    public JmsDestinationReferenceDescriptor getJmsDestinationReferenceByName(String name) {
        for (Iterator itr = this.getJmsDestinationReferenceDescriptors().iterator(); itr.hasNext();) {
            JmsDestinationReferenceDescriptor jdr = (JmsDestinationReferenceDescriptor) itr.next();
            if (jdr.getName().equals(name)) {
                return jdr;
            }
        }
        throw new IllegalArgumentException(localStrings.getLocalString(
                "enterprise.deployment.exceptionbeanhasnojmsdestrefbyname",
                "This bean {0} has no resource environment reference by the name of {1}",
                new Object[] {getName(), name}));
    }

    /**
     * Replaces the an environment proiperty with another one.
     */

    public void replaceEnvironmentProperty(EnvironmentProperty oldOne, EnvironmentProperty newOne) {
        environmentProperties.remove(oldOne);
        environmentProperties.add(newOne);
    }

    /**
     * Removes the given environment property from me.
     */

    public void removeEnvironmentProperty(EnvironmentProperty environmentProperty) {
        this.getEnvironmentProperties().remove(environmentProperty);

    }


    void removeRole(Role role) {
        //this.getPermissionedRoles().remove(role);
        this.getPermissionedMethodsByPermission().remove(new MethodPermission(role));
        Set roleReferences = new HashSet(this.getRoleReferences());
        for (Iterator itr = roleReferences.iterator(); itr.hasNext();) {
            RoleReference roleReference = (RoleReference) itr.next();
            if (roleReference.getRole().equals(role)) {
                roleReference.setValue("");
            }
        }
    }

    /**
     * Return the resource object corresponding to the supplied name or throw an illegal argument exception.
     */
    public ResourceReferenceDescriptor getResourceReferenceByName(String name) {
        for (Iterator itr = this.getResourceReferenceDescriptors().iterator(); itr.hasNext();) {
            ResourceReferenceDescriptor next = (ResourceReferenceDescriptor) itr.next();
            if (next.getName().equals(name)) {
                return next;
            }
        }
        throw new IllegalArgumentException(localStrings.getLocalString(
                "enterprise.deployment.exceptionbeanhasnoresourcerefbyname",
                "This bean {0} has no resource reference by the name of {1}",
                new Object[]{getName(), name}));
    }

    /**
     * Returns true if this ejb descriptor has resource references that are resolved.
     */
    public boolean hasResolvedResourceReferences() {
        if (!this.getResourceReferenceDescriptors().isEmpty()) {
            return false;
        } else {
            for (Iterator itr = this.getResourceReferenceDescriptors().iterator(); itr.hasNext();) {
                ResourceReferenceDescriptor resourceReference = (ResourceReferenceDescriptor) itr.next();
                if (resourceReference.isResolved()) {
                    return true;
                }
            }
        }
        return false;
    }


    public Set<EntityManagerFactoryReferenceDescriptor>
    getEntityManagerFactoryReferenceDescriptors() {

        return entityManagerFactoryReferences;
    }

    /**
     * Return the entity manager factory reference descriptor corresponding to
     * the given name.
     */
    public EntityManagerFactoryReferenceDescriptor
    getEntityManagerFactoryReferenceByName(String name) {
        for (EntityManagerFactoryReferenceDescriptor next :
                getEntityManagerFactoryReferenceDescriptors()) {

            if (next.getName().equals(name)) {
                return next;
            }
        }
        throw new IllegalArgumentException(localStrings.getLocalString(
                "enterprise.deployment.exceptionbeanhasnoentitymgrfactoryrefbyname",
                "This ejb {0} has no entity manager factory reference by the name of {1}",
                new Object[]{getName(), name}));
    }

    public void addEntityManagerFactoryReferenceDescriptor
            (EntityManagerFactoryReferenceDescriptor reference) {

        if (getEjbBundleDescriptor() != null) {
            reference.setReferringBundleDescriptor
                    (getEjbBundleDescriptor());
        }
        entityManagerFactoryReferences.add(reference);
    }

    public Set<EntityManagerReferenceDescriptor>
    getEntityManagerReferenceDescriptors() {

        return entityManagerReferences;
    }

    /**
     * Return the entity manager factory reference descriptor corresponding to
     * the given name.
     */
    public EntityManagerReferenceDescriptor
    getEntityManagerReferenceByName(String name) {
        for (EntityManagerReferenceDescriptor next :
                getEntityManagerReferenceDescriptors()) {

            if (next.getName().equals(name)) {
                return next;
            }
        }
        throw new IllegalArgumentException(localStrings.getLocalString(
                "enterprise.deployment.exceptionbeanhasnoentitymgrrefbyname",
                "This ejb {0} has no entity manager reference by the name of {1}",
                new Object[]{getName(), name}));
    }

    public void addEntityManagerReferenceDescriptor
            (EntityManagerReferenceDescriptor reference) {
        if (getEjbBundleDescriptor() != null) {
            reference.setReferringBundleDescriptor
                    (getEjbBundleDescriptor());
        }
        this.getEntityManagerReferenceDescriptors().add(reference);
    }


    /**
     * Return a copy of the structure holding the environ,ent properties.
     */
    public Set<EnvironmentProperty> getEnvironmentProperties() {
        return environmentProperties;
    }


    /**
     * Add the supplied environment property to the ejb descriptor's list.
     */
    public void addEnvironmentProperty(EnvironmentProperty environmentProperty) {
        if (environmentProperties.contains(environmentProperty)) {
            replaceEnvironmentProperty(environmentProperty, environmentProperty);
        } else {
            environmentProperties.add(environmentProperty);
        }
    }

    /**
     * Return a copy of the role references set.
     */
    public Set<RoleReference> getRoleReferences() {
        if (roleReferences == null) {
            roleReferences = new HashSet();
        }
        return roleReferences;
    }

    /**
     * Adds a role reference.
     */

    public void addRoleReference(RoleReference roleReference) {
        //_logger.log(Level.FINE,"add " + roleReference);
        this.getRoleReferences().add(roleReference);

    }

    /**
     * Removes a role reference.
     */

    public void removeRoleReference(RoleReference roleReference) {
        this.getRoleReferences().remove(roleReference);

    }

    /**
     * Returns a matching role reference by name or throw an IllegalArgumentException.
     */
    public RoleReference getRoleReferenceByName(String roleReferenceName) {
        for (Iterator itr = this.getRoleReferences().iterator(); itr.hasNext();) {
            RoleReference nextRR = (RoleReference) itr.next();
            if (nextRR.getName().equals(roleReferenceName)) {
                return nextRR;
            }
        }
        return null;
    }

    public List<InjectionCapable>
    getInjectableResourcesByClass(String className) {
        return bundleDescriptor.getInjectableResourcesByClass
                (className, this);
    }

    public InjectionInfo getInjectionInfoByClass(String className) {
        return bundleDescriptor.getInjectionInfoByClass(className, this);
    }

    /**
     * Gets the containing ejb bundle descriptor..
     */
    public EjbBundleDescriptor getEjbBundleDescriptor() {
        return bundleDescriptor;
    }

    public void setEjbBundleDescriptor(EjbBundleDescriptor bundleDescriptor) {
        this.bundleDescriptor = bundleDescriptor;
    }

    /**
     * Gets the application to which this ejb descriptor belongs.
     */
    public Application getApplication() {
        if (getEjbBundleDescriptor() != null) {
            return getEjbBundleDescriptor().getApplication();
        }
        return null;
    }

    /**
     * Returns the full set of method descriptors I have (from all the methods on my home and remote interfaces).
     */
    public Set getMethodDescriptors() {

        ClassLoader classLoader = getEjbBundleDescriptor().getClassLoader();
        Set methods = getBusinessMethodDescriptors();

        try {
            if (isRemoteInterfacesSupported()) {
                addAllInterfaceMethodsIn(methods, classLoader.loadClass(getHomeClassName()), MethodDescriptor.EJB_HOME);
            }

            if (isLocalInterfacesSupported()) {
                addAllInterfaceMethodsIn(methods, classLoader.loadClass(getLocalHomeClassName()), MethodDescriptor.EJB_LOCALHOME);
            }

        } catch (Throwable t) {
            /*
            t.printStackTrace();
            _logger.log(Level.SEVERE,localStrings.getLocalString(
                                   "enterprise.deployment.errorloadingclass",
                                   "Error loading class {0}", new Object [] {"(EjbDescriptor.getMethods())"}));
            */
            _logger.log(Level.SEVERE, "enterprise.deployment.backend.methodClassLoadFailure", new Object[]{"(EjbDescriptor.getMethods())"});

            throw new RuntimeException(t);
        }
        return methods;
    }

    /**
     * Returns the full set of transactional business method descriptors I have.
     */
    public Set getTxBusinessMethodDescriptors() {
        Set txBusMethods = getBusinessMethodDescriptors();
        if (isTimedObject()) {
            if (timedObjectMethod != null) {
                txBusMethods.add(timedObjectMethod);
            }
            // XXX TODO - add schedule methods
        }
        return txBusMethods;
    }

    /**
     * Returns the full set of security business method descriptors I have.
     */
    public Set getSecurityBusinessMethodDescriptors() {
        return getBusinessMethodDescriptors();
    }

    /**
     * Returns the set of local/remote/no-interface view business method descriptors I have.
     */
    public Set getClientBusinessMethodDescriptors() {
        return getLocalRemoteBusinessMethodDescriptors();
    }

    /**
     * Returns the full set of business method descriptors I have
     */
    private Set getLocalRemoteBusinessMethodDescriptors() {

        ClassLoader classLoader = getEjbBundleDescriptor().getClassLoader();

        Set methods = new HashSet();

        try {
            if (isRemoteInterfacesSupported()) {
                addAllInterfaceMethodsIn(methods, classLoader.loadClass(getRemoteClassName()), MethodDescriptor.EJB_REMOTE);
            }

            if (isRemoteBusinessInterfacesSupported()) {
                for (String intf : getRemoteBusinessClassNames()) {
                    addAllInterfaceMethodsIn(methods, classLoader.loadClass(intf), MethodDescriptor.EJB_REMOTE);
                }
            }

            if (isLocalInterfacesSupported()) {
                addAllInterfaceMethodsIn(methods, classLoader.loadClass(getLocalClassName()), MethodDescriptor.EJB_LOCAL);
            }

            if (isLocalBusinessInterfacesSupported()) {
                for (String intf : getLocalBusinessClassNames()) {
                    addAllInterfaceMethodsIn(methods, classLoader.loadClass(intf), MethodDescriptor.EJB_LOCAL);
                }
            }

            if (isLocalBean()) {
                addAllInterfaceMethodsIn(methods, classLoader.loadClass(getEjbClassName()), 
                        MethodDescriptor.EJB_LOCAL);                    
            }
        } catch (Throwable t) {
            _logger.log(Level.SEVERE, "enterprise.deployment.backend.methodClassLoadFailure", 
                    new Object[]{"(EjbDescriptor.getBusinessMethodDescriptors())"});

            throw new RuntimeException(t);
        }
        return methods;
    }

    /**
     * Returns the full set of business method descriptors I have
     */
    private Set getBusinessMethodDescriptors() {
        ClassLoader classLoader = getEjbBundleDescriptor().getClassLoader();
        Set methods = getLocalRemoteBusinessMethodDescriptors();

        try {
            if (hasWebServiceEndpointInterface()) {
                addAllInterfaceMethodsIn(methods, classLoader.loadClass(getWebServiceEndpointInterfaceName()), MethodDescriptor.EJB_WEB_SERVICE);
            }
        } catch (Throwable t) {
            _logger.log(Level.SEVERE, "enterprise.deployment.backend.methodClassLoadFailure", new Object[]{"(EjbDescriptor.getBusinessMethodDescriptors())"});

            throw new RuntimeException(t);
        }
        return methods;
    }

    protected void addAllInterfaceMethodsIn(Collection methodDescriptors, Class c, String methodIntf) {
        Method[] methods = c.getMethods();
        for (int i = 0; i < methods.length; i++) {
            if( methods[i].getDeclaringClass() != java.lang.Object.class ) {
                methodDescriptors.add(getMethodDescriptorFor(methods[i], methodIntf));
            }
        }
    }

    /**
     * @return the MethodDescriptor for the given Method object
     */
    public MethodDescriptor getMethodDescriptorFor(Method m, String methodIntf) {
        MethodDescriptor result = allMethodDescriptors.get(m);
        if (result == null) {
            result = new MethodDescriptor(m, methodIntf);
            MethodDescriptor md = allMethodDescriptors.putIfAbsent(m, result);
            if (md != null) {
                // Another thread already added its version
                result = md;
            }
        }
        return result;
    }

    /**
     * @return the collection of MethodDescriptors to which ContainerTransactions
     *         may be assigned.
     */
    public Collection getTransactionMethodDescriptors() {

        return getTransactionMethods(getEjbBundleDescriptor().getClassLoader());
    }

    /**
     * @return a collection of MethodDescriptor for methods which may
     *         have a associated transaction attribute
     */
    protected Collection getTransactionMethods(ClassLoader classLoader) {

        try {
            ClassLoader cl = getEjbBundleDescriptor().getClassLoader();
            BeanMethodCalculator bmc = Globals.getDefaultHabitat().getComponent(BeanMethodCalculator.class);
            if (bmc!=null) {
                return bmc.getTransactionalMethodsFor(this, classLoader);
            } else {
                _logger.log(Level.FINE, "enterprise.deploymnet.ejbcontainernotinstalled");
                return null;
            }
        } catch (Throwable t) {

            _logger.log(Level.SEVERE, "enterprise.deployment.backend.methodClassLoadFailure", new Object[]{"(EjbDescriptor.getMethods())"});

            throw new RuntimeException(t);
        }
    }

    /**
     * Return the set of method objects representing no-interface view
     */
    public Set<Method> getOptionalLocalBusinessMethods() {
        Set<Method> methods = new HashSet<Method>();
        try {
            Class c = getEjbBundleDescriptor().getClassLoader().loadClass(getEjbClassName());
            Method[] ms = c.getMethods();
            for (Method m : ms) {
                if (m.getDeclaringClass() != Object.class) {
                    methods.add(m);
                }
            }
        } catch (Throwable t) {
            _logger.log(Level.SEVERE, "enterprise.deployment.backend.methodClassLoadFailure", new Object[]{"(EjbDescriptor.getMethods())"});
            throw new RuntimeException(t);
        }

         return methods;
    }

    /**
     * Return the set of method objects on my home and remote interfaces.
     */

    public Vector getMethods() {
        return getMethods(getEjbBundleDescriptor().getClassLoader());
    }


    /**
     * Return the ejb method objects, i.e. the methods on the home and remote interfaces.
     */
    public Vector getMethods(ClassLoader classLoader) {
        try {
            ClassLoader cl = getEjbBundleDescriptor().getClassLoader();
            BeanMethodCalculator bmc = Globals.getDefaultHabitat().getComponent(BeanMethodCalculator.class);
            if (bmc!=null) {
                return bmc.getMethodsFor(this, classLoader);
            } else {
                _logger.log(Level.FINE, "enterprise.deploymnet.ejbcontainernotinstalled");
                return new Vector();
            }
        } catch (Throwable t) {
            _logger.log(Level.SEVERE, "enterprise.deployment.backend.methodClassLoadFailure", new Object[]{"(EjbDescriptor.getMethods())"});
            throw new RuntimeException(t);
        }
    }

    /**
     * Return a Vector of the Field objetcs of this ejb.
     */
    public Vector getFields() {
        Vector fieldsVector = new Vector();
        Class ejb = null;
        try {
            ClassLoader cl = getEjbBundleDescriptor().getClassLoader();
            ejb = cl.loadClass(this.getEjbClassName());
        } catch (Throwable t) {
            _logger.log(Level.SEVERE, "enterprise.deployment.backend.methodClassLoadFailure", new Object[]{this.getEjbClassName()});

            return fieldsVector;
        }
        Field[] fields = ejb.getFields();
        for (int i = 0; i < fields.length; i++) {
            fieldsVector.addElement(fields[i]);
        }
        return fieldsVector;

    }

    public Vector getFieldDescriptors() {
        Vector fields = this.getFields();
        Vector fieldDescriptors = new Vector();
        for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
            Field field = (Field) fields.elementAt(fieldIndex);
            fieldDescriptors.insertElementAt(new FieldDescriptor(field), fieldIndex);
        }
        return fieldDescriptors;
    }

    void doMethodDescriptorConversions() throws Exception {
        // container transactions first
        Hashtable transactions = this.getMethodContainerTransactions();
        //_logger.log(Level.FINE,"Pre conversion = " + transactions);
        Hashtable convertedTransactions = new Hashtable();
        Collection transactionMethods = this.getTransactionMethodDescriptors();
        for (Enumeration e = transactions.keys(); e.hasMoreElements();) {
            MethodDescriptor md = (MethodDescriptor) e.nextElement();
            ContainerTransaction ct = (ContainerTransaction) transactions.get(md);
            for (Enumeration mds = md.doStyleConversion(this, transactionMethods).elements(); mds.hasMoreElements();) {
                MethodDescriptor next = (MethodDescriptor) mds.nextElement();
                convertedTransactions.put(next, new ContainerTransaction(ct));
            }
        }
        //_logger.log(Level.FINE,"Post conversion = " + convertedTransactions);
        setMethodContainerTransactions(convertedTransactions);

        convertMethodPermissions();
    }

    public void removeEjbReferencer(EjbReferenceDescriptor ref) {
        ejbReferencersPointingToMe.remove(ref);
    }

    // called from EjbReferenceDescriptor.setEjbDescriptor
    void addEjbReferencer(EjbReferenceDescriptor ref) {
        ejbReferencersPointingToMe.add(ref);
    }

    // called from EjbEntityDescriptor.replaceEntityDescriptor etc
    public Set getAllEjbReferencers() {
        return ejbReferencersPointingToMe;
    }


    // Called from EjbBundleDescriptor only
    public void setUniqueId(long id) {
        uniqueId = id;
    }

    public long getUniqueId() {
        return uniqueId;
    }

    /**
     * Returns a formatted String of the attributes of this object.
     */
    public void print(StringBuffer toStringBuffer) {
        super.print(toStringBuffer);
        toStringBuffer.append("\n ejbClassName ").append(ejbClassName);
        toStringBuffer.append("\n transactionType ").append(transactionType);
        toStringBuffer.append("\n methodContainerTransactions ").append(getMethodContainerTransactions());
        toStringBuffer.append("\n environmentProperties ");
        if (environmentProperties != null)
            printDescriptorSet(environmentProperties, toStringBuffer);
        toStringBuffer.append("\n ejbReferences ");
        if (ejbReferences != null)
            printDescriptorSet(ejbReferences, toStringBuffer);
        toStringBuffer.append("\n jmsDestReferences ");
        if (jmsDestReferences != null)
            printDescriptorSet(jmsDestReferences, toStringBuffer);
        toStringBuffer.append("\n messageDestReferences ");
        if (messageDestReferences != null)
            printDescriptorSet(messageDestReferences, toStringBuffer);
        toStringBuffer.append("\n resourceReferences ");
        if (resourceReferences != null)
            printDescriptorSet(resourceReferences, toStringBuffer);
        toStringBuffer.append("\n serviceReferences ");
        if (serviceReferences != null)
            printDescriptorSet(serviceReferences, toStringBuffer);
        toStringBuffer.append("\n roleReferences ");
        if (roleReferences != null)
            printDescriptorSet(roleReferences, toStringBuffer);
        for (Iterator e = this.getPermissionedMethodsByPermission().keySet().iterator(); e.hasNext();) {
            MethodPermission nextPermission = (MethodPermission) e.next();
            toStringBuffer.append("\n method-permission->method: ");
            nextPermission.print(toStringBuffer);
            toStringBuffer.append(" -> ").append(this.getPermissionedMethodsByPermission().get(nextPermission));
        }
    }

    private void printDescriptorSet(Set descSet, StringBuffer sbuf) {
        for (Iterator itr = descSet.iterator(); itr.hasNext();) {
            Object obj = itr.next();
            if (obj instanceof Descriptor)
                ((Descriptor) obj).print(sbuf);
            else
                sbuf.append(obj);
        }
    }

    /**
     * visit the descriptor and all sub descriptors with a DOL visitor implementation
     *
     * @param aVisitor a visitor to traverse the descriptors
     */
    public void visit(DescriptorVisitor aVisitor) {
        if (aVisitor instanceof EjbVisitor) {
            visit((EjbVisitor) aVisitor);
        } else {
            super.visit(aVisitor);
        }
    }

    /**
     * visit the descriptor and all sub descriptors with a DOL visitor implementation
     *
     * @param aVisitor a visitor to traverse the descriptors
     */
    public void visit(EjbVisitor aVisitor) {
        aVisitor.accept(this);

        // Visit all injectables first.  In some cases, basic type information
        // has to be derived from target inject method or inject field.
        for (InjectionCapable injectable :
                bundleDescriptor.getInjectableResources(this)) {
            aVisitor.accept(injectable);
        }

        for (Iterator itr = ejbReferences.iterator(); itr.hasNext();) {
            EjbReference aRef = (EjbReference) itr.next();
            aVisitor.accept(aRef);
        }
        for (Iterator e = getPermissionedMethodsByPermission().keySet().iterator(); e.hasNext();) {
            MethodPermission nextPermission = (MethodPermission) e.next();
            Set methods = (Set) getPermissionedMethodsByPermission().get(nextPermission);
            aVisitor.accept(nextPermission, methods.iterator());
        }
        if (getStyledPermissionedMethodsByPermission() != null) {
            for (Iterator e = getStyledPermissionedMethodsByPermission().keySet().iterator(); e.hasNext();) {
                MethodPermission nextPermission = (MethodPermission) e.next();
                Set methods = (Set) getStyledPermissionedMethodsByPermission().get(nextPermission);
                aVisitor.accept(nextPermission, methods.iterator());
            }
        }
        for (Iterator e = getRoleReferences().iterator(); e.hasNext();) {
            RoleReference roleRef = (RoleReference) e.next();
            aVisitor.accept(roleRef);
        }
        for (Iterator e = getMethodContainerTransactions().keySet().iterator(); e.hasNext();) {
            MethodDescriptor md = (MethodDescriptor) e.next();
            ContainerTransaction ct = (ContainerTransaction) getMethodContainerTransactions().get(md);
            aVisitor.accept(md, ct);
        }
        for (Iterator e = getEnvironmentProperties().iterator(); e.hasNext();) {
            EnvironmentProperty envProp = (EnvironmentProperty) e.next();
            aVisitor.accept(envProp);
        }

        for (Iterator it = getResourceReferenceDescriptors().iterator();
             it.hasNext();) {
            ResourceReferenceDescriptor next =
                    (ResourceReferenceDescriptor) it.next();
            aVisitor.accept(next);
        }

        for (Iterator it = getJmsDestinationReferenceDescriptors().iterator();
             it.hasNext();) {
            JmsDestinationReferenceDescriptor next =
                    (JmsDestinationReferenceDescriptor) it.next();
            aVisitor.accept(next);
        }

        for (Iterator it = getMessageDestinationReferenceDescriptors().iterator();
             it.hasNext();) {
            MessageDestinationReferencer next =
                    (MessageDestinationReferencer) it.next();
            aVisitor.accept(next);
        }

        // If this is a message bean, it can be a message destination
        // referencer as well.
        if (getType().equals(EjbMessageBeanDescriptor.TYPE)) {
            MessageDestinationReferencer msgDestReferencer =
                    (MessageDestinationReferencer) this;
            if (msgDestReferencer.getMessageDestinationLinkName() != null) {
                aVisitor.accept(msgDestReferencer);
            }
        }

        Set serviceRefs = getServiceReferenceDescriptors();
        for (Iterator itr = serviceRefs.iterator(); itr.hasNext();) {
            aVisitor.accept((ServiceReferenceDescriptor) itr.next());
        }
    }


}
    
