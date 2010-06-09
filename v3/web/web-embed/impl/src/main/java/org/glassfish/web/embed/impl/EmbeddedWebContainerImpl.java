/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2010 Sun Microsystems, Inc. All rights reserved.
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
 *
 */

package org.glassfish.web.embed.impl;

import java.beans.PropertyVetoException;
import java.io.File;

import java.util.*;
import java.util.logging.*;

import com.sun.enterprise.config.serverbeans.HttpService;
import com.sun.grizzly.config.dom.Http;
import com.sun.grizzly.config.dom.NetworkConfig;
import com.sun.grizzly.config.dom.NetworkListener;
import com.sun.grizzly.config.dom.NetworkListeners;
import com.sun.grizzly.config.dom.Protocol;
import com.sun.grizzly.config.dom.Protocols;
import com.sun.grizzly.config.dom.ThreadPool;
import com.sun.grizzly.config.dom.Transport;
import com.sun.grizzly.config.dom.Transports;

import org.glassfish.api.container.Sniffer;
import org.glassfish.api.embedded.*;
import org.glassfish.api.embedded.web.*;
import org.glassfish.api.embedded.web.config.*;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.component.*;
import org.jvnet.hk2.config.*;

import org.apache.catalina.Container;
import org.apache.catalina.Engine;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.Realm;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.ContextConfig;
import org.apache.catalina.startup.Embedded;
import org.apache.catalina.Connector;
import org.omg.CORBA.DynAnyPackage.*;

/**
 * Class representing an embedded web container, which supports the
 * programmatic creation of different types of web protocol listeners
 * and virtual servers, and the registration of static and dynamic
 * web resources into the URI namespace.
 *  
 * @author Amy Roh
 */
@Service
public class EmbeddedWebContainerImpl implements EmbeddedWebContainer {

    @Inject
    NetworkConfig config;
    
    @Inject
    Habitat habitat;

    @Inject
    HttpService httpService;
    
    private static Logger log = 
            Logger.getLogger(EmbeddedWebContainerImpl.class.getName());
      
    
    // ----------------------------------------------------------- Constructors
    

    // ----------------------------------------------------- Instance Variables

    Inhabitant<? extends org.glassfish.api.container.Container> webContainer;

    private VirtualServer defaultVirtualServer = null;
    
    Inhabitant<?> embeddedInhabitant;
    
    private Embedded embedded = null;
    
    private Engine engine = null;
    
    private File path = null;
    
    private String defaultDomain = "com.sun.appserv";
    
    private boolean listings;

    private int portNumber;

    private String listenerName = "embedded-listener";

    private String defaultvs = "server";

    private String securityEnabled = "false";

    private List<WebListener> listeners = new ArrayList<WebListener>();

    // --------------------------------------------------------- Public Methods

    public void setConfiguration(WebBuilder builder) {
        setPath(builder.getDocRootDir());
        listings = builder.getListings();
    }

    /**
     * Returns the list of sniffers associated with this embedded container
     * @return list of sniffers
     */
    public List<Sniffer> getSniffers() {
        List<Sniffer> sniffers = new ArrayList<Sniffer>();
        sniffers.add(habitat.getComponent(Sniffer.class, "web"));
        sniffers.add(habitat.getComponent(Sniffer.class, "weld"));        
        Sniffer security = habitat.getComponent(Sniffer.class, "Security");
        if (security!=null) {
            sniffers.add(security);
        }
        return sniffers;
    }

    public void bind(Port port, String protocol) {

        log.info("EmbeddedWebContainer binding port "+port.getPortNumber()+" protocol "+protocol);

        portNumber = port.getPortNumber();
        listenerName = getListenerName();

        if (protocol.equals(Port.HTTP_PROTOCOL)) {
            securityEnabled = "false";
        } if (protocol.equals(Port.HTTPS_PROTOCOL)) {
            securityEnabled = "true";
        }

        try {
            ConfigSupport.apply(new SingleConfigCode<Protocols>() {
                public Object run(Protocols param) throws TransactionFailure {
                    final Protocol protocol = param.createChild(Protocol.class);
                    protocol.setName(listenerName);
                    protocol.setSecurityEnabled(securityEnabled);
                    param.getProtocol().add(protocol);
                    final Http http = protocol.createChild(Http.class);
                    http.setDefaultVirtualServer(defaultvs);
                    protocol.setHttp(http);
                    return protocol;
                }
            }, config.getProtocols());
            ConfigSupport.apply(new ConfigCode() {
                public Object run(ConfigBeanProxy... params) throws TransactionFailure {
                    NetworkListeners listeners = (NetworkListeners) params[0];
                    Transports transports = (Transports) params[1];
                    final NetworkListener listener = listeners.createChild(NetworkListener.class);
                    listener.setName(listenerName);
                    listener.setPort(Integer.toString(portNumber));
                    listener.setProtocol(listenerName);
                    listener.setThreadPool("http-thread-pool");
                    if (listener.findThreadPool() == null) {
                        final ThreadPool pool = listeners.createChild(ThreadPool.class);
                        pool.setName(listenerName);
                        listener.setThreadPool(listenerName);
                    }
                    listener.setTransport("tcp");
                    if (listener.findTransport() == null) {
                        final Transport transport = transports.createChild(Transport.class);
                        transport.setName(listenerName);
                        listener.setTransport(listenerName);
                    }
                    listeners.getNetworkListener().add(listener);
                    return listener;
                }
            }, config.getNetworkListeners(), config.getTransports());

            com.sun.enterprise.config.serverbeans.VirtualServer vs = httpService.getVirtualServerByName(defaultvs);
            ConfigSupport.apply(new SingleConfigCode<com.sun.enterprise.config.serverbeans.VirtualServer>() {
                public Object run(com.sun.enterprise.config.serverbeans.VirtualServer avs) throws PropertyVetoException {
                    avs.addNetworkListener(listenerName);
                    return avs;
                }
            }, vs);
        } catch (Exception e) {
            removeListener();
            e.printStackTrace();
        }
    }

    private String getListenerName() {
        int i = 1;
        String name = "embedded-listener";
        while (existsListener(name)) {
            name = "embedded-listener-" + i++;
        }
        return name;
    }

    private boolean existsListener(String lName) {
        for (NetworkListener nl : config.getNetworkListeners().getNetworkListener()) {
            if (nl.getName().equals(lName)) {
                return true;
            }
        }
        return false;
    }

    private void removeListener() {
        try {
            ConfigSupport.apply(new ConfigCode() {
                public Object run(ConfigBeanProxy[] params) throws PropertyVetoException, TransactionFailure {
                    final NetworkListeners nt = (NetworkListeners) params[0];
                    final com.sun.enterprise.config.serverbeans.VirtualServer vs = (com.sun.enterprise.config.serverbeans.VirtualServer) params[1];
                    final Protocols protocols = (Protocols) params[2];
                    List<Protocol> protos = protocols.getProtocol();
                    for (Protocol proto : protos) {
                        if (proto.getName().equals(listenerName)) {
                            protos.remove(proto);
                            break;
                        }
                    }
                    final List<NetworkListener> list = nt.getNetworkListener();
                    for (NetworkListener listener : list) {
                        if (listener.getName().equals(listenerName)) {
                            list.remove(listener);
                            break;
                        }
                    }
                    String regex = listenerName + ",?";
                    String lss = vs.getNetworkListeners();
                    if (lss != null) {
                        vs.setNetworkListeners(lss.replaceAll(regex, ""));
                    }
                    return null;
                }
            }, config.getNetworkListeners(),
                httpService.getVirtualServerByName(defaultvs),
                config.getProtocols());
        } catch (TransactionFailure tf) {
            tf.printStackTrace();
            throw new RuntimeException(tf);
        }
    }
    
    /**
     * Starts this <tt>EmbeddedWebContainer</tt> and any of the
     * <tt>WebListener</tt> and <tt>VirtualServer</tt> instances
     * registered with it.
     *
     * <p>This method also creates and starts a default
     * <tt>VirtualServer</tt> with id <tt>server</tt> and hostname
     * <tt>localhost</tt>, as well as a default <tt>WebListener</tt>
     * with id <tt>http-listener-1</tt> on port 8080 if no other virtual server 
     * or listener configuration exists.
     * In order to change any of these default settings, 
     * {@link #start(WebContainerConfig)} may be called.
     * 
     * @throws Exception if an error occurs during the start up of this
     * <tt>EmbeddedWebContainer</tt> or any of its registered
     * <tt>WebListener</tt> or <tt>VirtualServer</tt> instances 
     */
    public void start() throws LifecycleException {
   
        if (log.isLoggable(Level.INFO)) { 
            log.info("EmbeddedWebContainer is starting");
        }
        
        webContainer = habitat.getInhabitant(org.glassfish.api.container.Container.class,
                "com.sun.enterprise.web.WebContainer");
        if (webContainer==null) {
            log.severe("Cannot find webcontainer implementation");
            throw new LifecycleException(new Exception("Cannot find web container implementation"));
        }
        
        embeddedInhabitant = habitat.getInhabitantByType("com.sun.enterprise.web.EmbeddedWebContainer");
        if (embeddedInhabitant==null) {
            log.severe("Cannot find embedded implementation");
            throw new LifecycleException(new Exception("Cannot find embedded implementation"));
        }
        
        // force the start
        try {
            webContainer.get();
            embedded = (Embedded)embeddedInhabitant.get();
            Engine[] engines = embedded.getEngines();
            if (engines!=null) {
                engine = engines[0];
            } else {
                throw new LifecycleException(new Exception("Cannot find engine implementation"));
            }
        
            File docRoot = getPath();
            VirtualServer vs = findVirtualServer(defaultvs);
            if (vs != null) {
                defaultVirtualServer = vs;
            } else {
                defaultVirtualServer = createVirtualServer(defaultvs, docRoot);
                addVirtualServer(defaultVirtualServer);
            }
            if (listings) {
                for (Context context : defaultVirtualServer.getContexts()) {
                    context.setDirectoryListing(listings);
                }
            }
            if (getWebListeners()==null) {
                if (log.isLoggable(Level.INFO)) {
                    log.info("Listener doesn't exist - creating a new listener at port 8080");
                }
                WebListener listener = createWebListener(listenerName, WebListenerImpl.class);
                listener.setPort(8080);
                addWebListener(listener);
            }
            
        } catch (Exception e) {
            throw new LifecycleException(e);
        }
    }

    
    /**
     * Stops this <tt>EmbeddedWebContainer</tt> and any of the
     * <tt>WebListener</tt> and <tt>VirtualServer</tt> instances
     * registered with it.
     *
     * @throws Exception if an error occurs during the shut down of this
     * <tt>EmbeddedWebContainer</tt> or any of its registered
     * <tt>WebListener</tt> or <tt>VirtualServer</tt> instances 
     */
    public void stop() throws LifecycleException {

       if (webContainer!=null && webContainer.isInstantiated()) {
           try {
               webContainer.release();
           } catch (Exception e) {
               throw new LifecycleException(e);
           }
       }       
       
       if (webContainer!=null && webContainer.isInstantiated()) {
           try {
               webContainer.release();
           } catch (Exception e) {
               throw new LifecycleException(e);
           }
       }
    }
   
    
    /**
     * Creates a <tt>Context</tt>, configures it with the given
     * docroot and classloader, and registers it with the default
     * <tt>VirtualServer</tt>.
     *
     * <p>The given classloader will be set as the thread's context
     * classloader whenever the new <tt>Context</tt> or any of its
     * resources are asked to process a request.
     * If a <tt>null</tt> classloader is passed, the classloader of the
     * class on which this method is called will be used.
     *
     * @param docRoot the docroot of the <tt>Context</tt>
     * @param contextRoot
     * @param classLoader the classloader of the <tt>Context</tt>
     *
     * @return the new <tt>Context</tt>
     */
    public Context createContext(File docRoot, String contextRoot, 
            ClassLoader classLoader) {
        
        if (log.isLoggable(Level.INFO)) {
            log.info("Creating context '" + contextRoot + "' with docBase '" +
                     docRoot.getPath() + "'");
        }

        ContextImpl context = new ContextImpl();
        context.setDocBase(docRoot.getPath());
        context.setPath(contextRoot);
        context.setDirectoryListing(listings);
        if (classLoader != null) {
            context.setParentClassLoader(classLoader);
        } else {
            context.setParentClassLoader(
                    Thread.currentThread().getContextClassLoader());
        }        
                
        Realm realm = habitat.getByContract(Realm.class);
        // XXX RealmAdapter.initializeRealm
        context.setRealm(realm);
                
        ContextConfig config = new ContextConfig();
        ((Lifecycle) context).addLifecycleListener(config);

        try {
            if (defaultVirtualServer!=null) {
                defaultVirtualServer.addContext(context, contextRoot);
            }
        } catch (Exception ex) {
            log.severe("Couldn't add context "+contextRoot+" to default virtual server");
        }
        
        return context;
        
    }

    /**
     * Creates a <tt>Context</tt> and configures it with the given
     * docroot and classloader.
     *
     * <p>The given classloader will be set as the thread's context
     * classloader whenever the new <tt>Context</tt> or any of its
     * resources are asked to process a request.
     * If a <tt>null</tt> classloader is passed, the classloader of the
     * class on which this method is called will be used.
     *
     * <p>In order to access the new <tt>Context</tt> or any of its 
     * resources, the <tt>Context</tt> must be registered with a
     * <tt>VirtualServer</tt> that has been started.
     *
     * @param docRoot the docroot of the <tt>Context</tt>
     * @param classLoader the classloader of the <tt>Context</tt>
     *
     * @return the new <tt>Context</tt>
     *
     * @see VirtualServer#addContext
     */
    public Context createContext(File docRoot, ClassLoader classLoader) {
        
        if (log.isLoggable(Level.INFO)) {
            log.info("Creating context with docBase '" + docRoot.getPath() + "'");
        }

        ContextImpl context = new ContextImpl();
        context.setDocBase(docRoot.getAbsolutePath());
        context.setDirectoryListing(listings);
        if (classLoader != null) {
            context.setParentClassLoader(classLoader);
        } else {
            context.setParentClassLoader(
                    Thread.currentThread().getContextClassLoader());
        }       
        
        Realm realm = habitat.getByContract(Realm.class);
        //XXX RealmAdapter.initializeRealm
        context.setRealm(realm);
        
        ContextConfig config = new ContextConfig();
        ((Lifecycle) context).addLifecycleListener(config);
        
        return context;
        
    }

    /**
     * Creates a <tt>WebListener</tt> from the given class type and
     * assigns the given id to it.
     *
     * @param id the id of the new <tt>WebListener</tt>
     * @param c the class from which to instantiate the
     * <tt>WebListener</tt>
     * 
     * @return the new <tt>WebListener</tt> instance
     *
     * @throws  IllegalAccessException if the given <tt>Class</tt> or
     * its nullary constructor is not accessible.
     * @throws  InstantiationException if the given <tt>Class</tt>
     * represents an abstract class, an interface, an array class,
     * a primitive type, or void; or if the class has no nullary
     * constructor; or if the instantiation fails for some other reason.
     * @throws ExceptionInInitializerError if the initialization
     * fails
     * @throws SecurityException if a security manager, <i>s</i>, is
     * present and any of the following conditions is met:
     *
     * <ul>
     * <li> invocation of <tt>{@link SecurityManager#checkMemberAccess
     * s.checkMemberAccess(this, Member.PUBLIC)}</tt> denies
     * creation of new instances of the given <tt>Class</tt>
     * <li> the caller's class loader is not the same as or an
     * ancestor of the class loader for the current class and
     * invocation of <tt>{@link SecurityManager#checkPackageAccess
     * s.checkPackageAccess()}</tt> denies access to the package
     * of this class
     * </ul>
     */
    public <T extends WebListener> T createWebListener(String id, Class<T> c) 
            throws InstantiationException, IllegalAccessException {
        
        T webListener = null;
        if (log.isLoggable(Level.INFO)) {
            log.info("Creating connector "+id);
        }
        
        try {
            webListener = c.newInstance();
            webListener.setId(id);
        } catch (Exception e) {
            log.severe("Couldn't create connector "+e.getMessage());
        } 
        
        return webListener;
        
    }

    /**
     * Adds the given <tt>WebListener</tt> to this
     * <tt>EmbeddedWebContainer</tt>.
     *
     * <p>If this <tt>EmbeddedWebContainer</tt> has already been started,
     * the given <tt>webListener</tt> will be started as well.
     *
     * @param webListener the <tt>WebListener</tt> to add
     *
     * @throws ConfigException if a <tt>WebListener</tt> with the
     * same id has already been registered with this
     * <tt>EmbeddedWebContainer</tt>
     * @throws LifecycleException if the given <tt>webListener</tt> fails
     * to be started
     */
    public void addWebListener(WebListener webListener) 
            throws ConfigException, LifecycleException {

        if (findWebListener(webListener.getId())==null) {
            listeners.add(webListener);
        } else {
            throw new ConfigException("Connector with name '"+
                    webListener.getId()+"' already exsits");           
        }
        
        if (log.isLoggable(Level.INFO)) {
            log.info("Added connector "+webListener.getId());
        }

        try {
            Ports ports = habitat.getComponent(Ports.class);
            Port port = ports.createPort(webListener.getPort());
            // TODO webListneer.protocol
            bind(port, "http");
        } catch (java.io.IOException ex) {
            throw new ConfigException(ex);
        }
        
    }

    /**
     * Finds the <tt>WebListener</tt> with the given id.
     *
     * @param id the id of the <tt>WebListener</tt> to find
     *
     * @return the <tt>WebListener</tt> with the given id, or
     * <tt>null</tt> if no <tt>WebListener</tt> with that id has been
     * registered with this <tt>EmbeddedWebContainer</tt>
     */
    public WebListener findWebListener(String id) {
        for (WebListener listener : listeners) {
            if (listener.getId().equals(id)) {
                return listener;
            }
        }
        return null;
    }

    /**
     * Gets the collection of <tt>WebListener</tt> instances registered
     * with this <tt>EmbeddedWebContainer</tt>.
     * 
     * @return the (possibly empty) collection of <tt>WebListener</tt>
     * instances registered with this <tt>EmbeddedWebContainer</tt>
     */
    public Collection<WebListener> getWebListeners() {
        return listeners;
    }

    /**
     * Stops the given <tt>webListener</tt> and removes it from this
     * <tt>EmbeddedWebContainer</tt>.
     *
     * @param webListener the <tt>WebListener</tt> to be stopped
     * and removed
     *
     * @throws LifecycleException if an error occurs during the stopping
     * or removal of the given <tt>webListener</tt>
     */
    public void removeWebListener(WebListener webListener)
        throws LifecycleException {

        if (listeners.contains(webListener)) {
            listeners.remove(webListener);
        } else {
            throw new LifecycleException(new ConfigException("Connector with name '"+
                    webListener.getId()+"' does not exsits"));
        }
        
    }

    /**
     * Creates a <tt>VirtualServer</tt> with the given id and docroot, and
     * maps it to the given <tt>WebListener</tt> instances.
     * 
     * @param id the id of the <tt>VirtualServer</tt>
     * @param docRoot the docroot of the <tt>VirtualServer</tt>
     * @param webListeners the list of <tt>WebListener</tt> instances from 
     * which the <tt>VirtualServer</tt> will receive requests
     * 
     * @return the new <tt>VirtualServer</tt> instance
     */
    public VirtualServer createVirtualServer(String id,
        File docRoot, WebListener...  webListeners) {
        
        if (log.isLoggable(Level.INFO)) {
            log.info("Created virtual server "+id+" with ports ");
        }
        VirtualServerImpl virtualServer = new VirtualServerImpl();
        virtualServer.setName(id);
        if (docRoot!=null) {
            virtualServer.setAppBase(docRoot.getPath());
        } 
        String[] names = new String[webListeners.length];
        for (int i=0; i<webListeners.length; i++) {
            names[i] = webListeners[i].getId();
            if (log.isLoggable(Level.INFO)) {
                log.info(""+ names[i]);
            }
        }
        virtualServer.setNetworkListenerNames(names);
        
        return virtualServer;
        
    }
    
    /**
     * Creates a <tt>VirtualServer</tt> with the given id and docroot, and
     * maps it to all <tt>WebListener</tt> instances.
     * 
     * @param id the id of the <tt>VirtualServer</tt>
     * @param docRoot the docroot of the <tt>VirtualServer</tt>
     * 
     * @return the new <tt>VirtualServer</tt> instance
     */    
    public VirtualServer createVirtualServer(String id, File docRoot) {
        
        if (log.isLoggable(Level.INFO)) {
            log.info("Created virtual server "+id);
        }
        VirtualServerImpl virtualServer = new VirtualServerImpl();
        virtualServer.setName(id);
        if (docRoot!=null) {
            virtualServer.setAppBase(docRoot.getPath());
        }     
        Ports ports = habitat.getComponent(Ports.class);
        // the port is used as unique identifier network listener name
        String[] portsArray = null;
        if (ports != null) {
            Collection<Port> coll = ports.getPorts();
            portsArray = new String[coll.size()];
            int i=0;
            for (Port port:coll) {
                portsArray[i] = Integer.toString(port.getPortNumber());
                if (log.isLoggable(Level.INFO)) {
                    log.info("port = "+portsArray[i]);
                }
                i++;
            }
            virtualServer.setNetworkListenerNames(portsArray);
        }
        
        return virtualServer;
        
    }

    /**
     * Adds the given <tt>VirtualServer</tt> to this
     * <tt>EmbeddedWebContainer</tt>.
     *
     * <p>If this <tt>EmbeddedWebContainer</tt> has already been started,
     * the given <tt>virtualServer</tt> will be started as well.
     *
     * @param virtualServer the <tt>VirtualServer</tt> to add
     *
     * @throws ConfigException if a <tt>VirtualServer</tt> with the
     * same id has already been registered with this
     * <tt>EmbeddedWebContainer</tt>
     * @throws LifecycleException if the given <tt>virtualServer</tt> fails
     * to be started
     */
    public void addVirtualServer(VirtualServer virtualServer)
        throws ConfigException, LifecycleException {
        
        if (findVirtualServer(virtualServer.getID())!=null) {
            throw new ConfigException("VirtualServer with id "+
                    virtualServer.getID()+" is already registered");

        } else {
            engine.setDefaultHost(virtualServer.getID());
            engine.addChild((Container)virtualServer);
        }
        if (log.isLoggable(Level.INFO)) {
            log.info("Added virtual server "+virtualServer.getID());
        }        
        
    }

    /**
     * Finds the <tt>VirtualServer</tt> with the given id.
     *
     * @param id the id of the <tt>VirtualServer</tt> to find
     *
     * @return the <tt>VirtualServer</tt> with the given id, or
     * <tt>null</tt> if no <tt>VirtualServer</tt> with that id has been
     * registered with this <tt>EmbeddedWebContainer</tt>
     */
    public VirtualServer findVirtualServer(String id) {
        return (VirtualServer)engine.findChild(id);
    }

    /**
     * Gets the collection of <tt>VirtualServer</tt> instances registered
     * with this <tt>EmbeddedWebContainer</tt>.
     * 
     * @return the (possibly empty) collection of <tt>VirtualServer</tt>
     * instances registered with this <tt>EmbeddedWebContainer</tt>
     */
    public Collection<VirtualServer> getVirtualServers(){
        
        List<VirtualServer> virtualServers = new ArrayList<VirtualServer>();
        for (Container child : engine.findChildren()) {
            if (child instanceof VirtualServer) {
                virtualServers.add((VirtualServer)child);
            }
        }
        return virtualServers;
        
    }

    /**
     * Stops the given <tt>virtualServer</tt> and removes it from this
     * <tt>EmbeddedWebContainer</tt>.
     *
     * @param virtualServer the <tt>VirtualServer</tt> to be stopped
     * and removed
     *
     * @throws LifecycleException if an error occurs during the stopping
     * or removal of the given <tt>virtualServer</tt>
     */
    public void removeVirtualServer(VirtualServer virtualServer) 
            throws LifecycleException {
           
        engine.removeChild((Container)virtualServer);
   
    }  
    
    /**
     * Sets the value of the context path
     * 
     * @param path - the path
     */
    public void setPath(File path) {
        this.path = path;
    }
  
    /**
     * Returning the value of the context path
     *
     * @return - the context path
     */
    public File getPath() {
        return path;
    }
    
    /**
     * Sets log level
     * 
     * @param level
     */
    public void setLogLevel(Level level) {
        log.setLevel(level);
    }   
    
}
