/*
 * Copyright (c) 2004, 2019 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package javax.xml.soap;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;


class FactoryFinder {
    private static final String JBOSS_SAAJ_IMPL_MODULE = "org.jboss.ws.saaj-impl";
    private static final Logger logger = Logger.getLogger("javax.xml.soap");

    private static final ServiceLoaderUtil.ExceptionHandler<SOAPException> EXCEPTION_HANDLER =
            new ServiceLoaderUtil.ExceptionHandler<SOAPException>() {
                @Override
                public SOAPException createException(Throwable throwable, String message) {
                    return new SOAPException(message, throwable);
                }
            };

    /**
     * Finds the implementation {@code Class} object for the given
     * factory type.  If it fails and {@code tryFallback} is {@code true}
     * finds the {@code Class} object for the given default class name.
     * The arguments supplied must be used in order
     * Note the default class name may be needed even if fallback
     * is not to be attempted in order to check if requested type is fallback.
     * <P>
     * This method is package private so that this code can be shared.
     *
     * @return the {@code Class} object of the specified message factory;
     *         may not be {@code null}
     *
     * @param factoryClass          factory abstract class or interface to be found
     * @param deprecatedFactoryId   deprecated name of a factory; it is used for types
     *                              where class name is different from a name
     *                              being searched (in previous spec).
     * @param defaultClassName      the implementation class name, which is
     *                              to be used only if nothing else
     *                              is found; {@code null} to indicate
     *                              that there is no default class name
     * @param tryFallback           whether to try the default class as a
     *                              fallback
     * @exception SOAPException if there is a SOAP error
     */
    @SuppressWarnings("unchecked")
    static <T> T find(Class<T> factoryClass,
                      String defaultClassName,
                      boolean tryFallback, String deprecatedFactoryId) throws SOAPException {

        ClassLoader tccl = ServiceLoaderUtil.contextClassLoader(EXCEPTION_HANDLER);
        String factoryId = factoryClass.getName();

        // Use the system property first
        String className = fromSystemProperty(factoryId, deprecatedFactoryId);
        if (className != null) {
            Object result = newInstance(className, defaultClassName, tccl);
            if (result != null) {
                return (T) result;
            }
        }

        // try to read from $java.home/lib/jaxm.properties
        className = fromJDKProperties(factoryId, deprecatedFactoryId);
        if (className != null) {
            Object result = newInstance(className, defaultClassName, tccl);
            if (result != null) {
                return (T) result;
            }
        }

        // standard services: java.util.ServiceLoader
        T factory = ServiceLoaderUtil.firstByServiceLoader(
                factoryClass,
                logger,
                EXCEPTION_HANDLER);
        if (factory != null) {
            return factory;
        }
        String serviceId = "META-INF/services/" + factoryId;
        ClassLoader moduleClassLoader = null;
        try {
            final Class<?> moduleClass = Class.forName("org.jboss.modules.Module");
            final Class<?> moduleIdentifierClass = Class.forName("org.jboss.modules.ModuleIdentifier");
            final Class<?> moduleLoaderClass = Class.forName("org.jboss.modules.ModuleLoader");
            final SecurityManager sm = System.getSecurityManager();
            Object moduleLoader = null;
            if (sm != null) {
                moduleLoader = AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                    public Object run() throws Exception {
                        return moduleClass.getMethod("getBootModuleLoader").invoke(null);

                    }
                });
            } else {
                moduleLoader = moduleClass.getMethod("getBootModuleLoader").invoke(null);
            }
            Object moduleIdentifier = moduleIdentifierClass.getMethod("create", String.class).invoke(null, JBOSS_SAAJ_IMPL_MODULE);
            final Object module = moduleLoaderClass.getMethod("loadModule", moduleIdentifierClass).invoke(moduleLoader, moduleIdentifier);
            if (sm != null) {
                try {
                    moduleClassLoader = AccessController.doPrivileged(new PrivilegedExceptionAction<ClassLoader>() {
                        @Override
                        public ClassLoader run() throws Exception {
                            return (ClassLoader) moduleClass.getMethod("getClassLoader").invoke(module);
                        }
                    });
                } catch (PrivilegedActionException pae) {
                    throw pae.getException();
                }
            } else {
                moduleClassLoader = (ClassLoader) moduleClass.getMethod("getClassLoader").invoke(module);
            }
        } catch (ClassNotFoundException e) {
           //ignore, JBoss Modules might not be available at all
        } catch (Exception e) {
           throw new SOAPException(e);
        }
        if (moduleClassLoader != null) {
           try {
              InputStream is = moduleClassLoader.getResourceAsStream("META-INF/services/" + deprecatedFactoryId);
              if (is == null) {
                  is = moduleClassLoader.getResourceAsStream(serviceId);
              }
              if( is!=null ) {
                  BufferedReader rd =
                      new BufferedReader(new InputStreamReader(is, "UTF-8"));
          
                  String factoryClassName = rd.readLine();
                  rd.close();

                  if (factoryClassName != null &&
                      ! "".equals(factoryClassName)) {
                      Object result = newInstance(factoryClassName, defaultClassName, moduleClassLoader);
                      if (result != null) {
                          return (T) result;
                      }
                  }
              }
          } catch( Exception ex ) {
          }
        }
        // try to find services in CLASSPATH
        className = fromMetaInfServices(deprecatedFactoryId, tccl);
        if (className != null) {
            logger.log(Level.WARNING,
                    "Using deprecated META-INF/services mechanism with non-standard property: {0}. " +
                            "Property {1} should be used instead.",
                    new Object[]{deprecatedFactoryId, factoryId});
            Object result = newInstance(className, defaultClassName, tccl);
            if (result != null) {
                return (T) result;
            }
        }

        // If not found and fallback should not be tried, return a null result.
        if (!tryFallback)
            return null;

        // We didn't find the class through the usual means so try the default
        // (built in) factory if specified.
        if (defaultClassName == null) {
            throw new SOAPException(
                    "Provider for " + factoryId + " cannot be found", null);
        }
        return (T) newInstance(defaultClassName, defaultClassName, tccl);
    }

    // in most cases there is no deprecated factory id
    static <T> T find(Class<T> factoryClass,
                      String defaultClassName,
                      boolean tryFallback) throws SOAPException {
        return find(factoryClass, defaultClassName, tryFallback, null);
    }

    private static Object newInstance(String className, String defaultClassName, ClassLoader tccl) throws SOAPException {
        return ServiceLoaderUtil.newInstance(
                className,
                defaultClassName,
                tccl,
                EXCEPTION_HANDLER);
    }

    // used only for deprecatedFactoryId;
    // proper factoryId searched by java.util.ServiceLoader
    private static String fromMetaInfServices(String deprecatedFactoryId, ClassLoader tccl) {
        String serviceId = "META-INF/services/" + deprecatedFactoryId;
        logger.log(Level.FINE, "Checking deprecated {0} resource", serviceId);

        try (InputStream is =
                     tccl == null ?
                             ClassLoader.getSystemResourceAsStream(serviceId)
                             :
                             tccl.getResourceAsStream(serviceId)) {

            if (is != null) {
                String factoryClassName;
                try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
                     BufferedReader rd = new BufferedReader(isr)) {
                    factoryClassName = rd.readLine();
                }

                logFound(factoryClassName);
                if (factoryClassName != null && !"".equals(factoryClassName)) {
                    return factoryClassName;
                }
            }

        } catch (IOException e) {
            // keep original behavior
        }
        return null;
    }

    private static String fromJDKProperties(String factoryId, String deprecatedFactoryId) {
        Path path = null;
        try {
            String JAVA_HOME = getSystemProperty("java.home");
            path = Paths.get(JAVA_HOME, "conf", "jaxm.properties");
            logger.log(Level.FINE, "Checking configuration in {0}", path);

            // to ensure backwards compatibility
            if (!Files.exists(path)) {
                path = Paths.get(JAVA_HOME, "lib", "jaxm.properties");
            }

            logger.log(Level.FINE, "Checking configuration in {0}", path);
            if (Files.exists(path)) {
                Properties props = new Properties();
                try (InputStream inputStream = Files.newInputStream(path)) {
                    props.load(inputStream);
                }

                // standard property
                logger.log(Level.FINE, "Checking property {0}", factoryId);
                String factoryClassName = props.getProperty(factoryId);
                logFound(factoryClassName);
                if (factoryClassName != null) {
                    return factoryClassName;
                }

                // deprecated property
                if (deprecatedFactoryId != null) {
                    logger.log(Level.FINE, "Checking deprecated property {0}", deprecatedFactoryId);
                    factoryClassName = props.getProperty(deprecatedFactoryId);
                    logFound(factoryClassName);
                    if (factoryClassName != null) {
                        logger.log(Level.WARNING,
                                "Using non-standard property: {0}. Property {1} should be used instead.",
                                new Object[]{deprecatedFactoryId, factoryId});
                        return factoryClassName;
                    }
                }
            }
        } catch (Exception ignored) {
            logger.log(Level.SEVERE, "Error reading SAAJ configuration from ["  + path +
                    "] file. Check it is accessible and has correct format.", ignored);
        }
        return null;
    }

    private static String fromSystemProperty(String factoryId, String deprecatedFactoryId) {
        String systemProp = getSystemProperty(factoryId);
        if (systemProp != null) {
            return systemProp;
        }
        if (deprecatedFactoryId != null) {
            systemProp = getSystemProperty(deprecatedFactoryId);
            if (systemProp != null) {
                logger.log(Level.WARNING,
                        "Using non-standard property: {0}. Property {1} should be used instead.",
                        new Object[] {deprecatedFactoryId, factoryId});
                return systemProp;
            }
        }
        return null;
    }

    private static String getSystemProperty(final String property) {
        logger.log(Level.FINE, "Checking system property {0}", property);
        String value = AccessController.doPrivileged(new PrivilegedAction<String>() {
            @Override
            public String run() {
                return System.getProperty(property);
            }
        });
        logFound(value);
        return value;
    }

    private static void logFound(String value) {
        if (value != null) {
            logger.log(Level.FINE, "  found {0}", value);
        } else {
            logger.log(Level.FINE, "  not found");
        }
    }

}
