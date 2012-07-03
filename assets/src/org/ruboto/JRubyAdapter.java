package org.ruboto;

import java.io.File;
import java.io.FilenameFilter;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Environment;
import dalvik.system.PathClassLoader;

public class JRubyAdapter {
    private static Object ruby;
    private static boolean isDebugBuild = false;
    private static PrintStream output = null;
    private static boolean initialized = false;

    private static String localContextScope = "SINGLETON";
    private static String localVariableBehavior = "TRANSIENT";

    private static String RUBOTO_CORE_VERSION_NAME;

    /*************************************************************************************************
     * 
     * Static Methods: ScriptingContainer config
     */

    public static void setLocalContextScope(String val) {
        localContextScope = val;
    }

    public static void setLocalVariableBehavior(String val) {
        localVariableBehavior = val;
    }

    /*************************************************************************************************
     * 
     * Static Methods: JRuby Execution
     */

    public static final FilenameFilter RUBY_FILES = new FilenameFilter() {
        public boolean accept(File dir, String fname) {
            return fname.endsWith(".rb");
        }
    };

	public static synchronized boolean isInitialized() {
		return initialized;
	}

	public static boolean usesPlatformApk() {
		return RUBOTO_CORE_VERSION_NAME != null;
	}
	
	public static String getPlatformVersionName() {
		return RUBOTO_CORE_VERSION_NAME;
	}
	
    public static synchronized boolean setUpJRuby(Context appContext) {
        return setUpJRuby(appContext, output == null ? System.out : output);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static synchronized boolean setUpJRuby(Context appContext, PrintStream out) {
        if (!initialized) {
            // BEGIN Ruboto HeapAlloc
            @SuppressWarnings("unused")
			byte[] arrayForHeapAllocation = new byte[13 * 1024 * 1024];
            arrayForHeapAllocation = null;
            // END Ruboto HeapAlloc
            setDebugBuild(appContext);
            Log.d("Setting up JRuby runtime (" + (isDebugBuild ? "DEBUG" : "RELEASE") + ")");
            System.setProperty("jruby.bytecode.version", "1.6");
            System.setProperty("jruby.interfaces.useProxy", "true");
            System.setProperty("jruby.management.enabled", "false");
            System.setProperty("jruby.objectspace.enabled", "false");
            System.setProperty("jruby.thread.pooling", "true");
            System.setProperty("jruby.native.enabled", "false");
            // System.setProperty("jruby.compat.version", "RUBY1_8"); // RUBY1_9 is the default

            // Uncomment these to debug Ruby source loading
            // System.setProperty("jruby.debug.loadService", "true");
            // System.setProperty("jruby.debug.loadService.timing", "true");


            ClassLoader classLoader;
            Class<?> scriptingContainerClass;
            String apkName = null;

            try {
                scriptingContainerClass = Class.forName("org.jruby.embed.ScriptingContainer");
                System.out.println("Found JRuby in this APK");
                classLoader = JRubyAdapter.class.getClassLoader();
                try {
                    apkName = appContext.getPackageManager().getApplicationInfo(appContext.getPackageName(), 0).sourceDir;
                } catch (NameNotFoundException e) {}
            } catch (ClassNotFoundException e1) {
                String packageName = "org.ruboto.core";
                try {
                	PackageInfo pkgInfo = appContext.getPackageManager().getPackageInfo(packageName, 0);
                    apkName = pkgInfo.applicationInfo.sourceDir;
                	RUBOTO_CORE_VERSION_NAME = pkgInfo.versionName;
                } catch (PackageManager.NameNotFoundException e2) {
                    out.println("JRuby not found in local APK:");
                    e1.printStackTrace(out);
                    out.println("JRuby not found in platform APK:");
                    e2.printStackTrace(out);
                    return false;
                }

                System.out.println("Found JRuby in platform APK");
                classLoader = new PathClassLoader(apkName, JRubyAdapter.class.getClassLoader());

                try {
                    scriptingContainerClass = Class.forName("org.jruby.embed.ScriptingContainer", true, classLoader);
                } catch (ClassNotFoundException e) {
                    // FIXME(uwe): ScriptingContainer not found in the platform APK...
                    e.printStackTrace();
                    return false;
                }
            }

            try {
                Class scopeClass = Class.forName("org.jruby.embed.LocalContextScope", true, scriptingContainerClass.getClassLoader());
                Class behaviorClass = Class.forName("org.jruby.embed.LocalVariableBehavior", true, scriptingContainerClass.getClassLoader());

                ruby = scriptingContainerClass
                         .getConstructor(scopeClass, behaviorClass)
                         .newInstance(Enum.valueOf(scopeClass, localContextScope), 
                                      Enum.valueOf(behaviorClass, localVariableBehavior));

                Class compileModeClass = Class.forName("org.jruby.RubyInstanceConfig$CompileMode", true, classLoader);
                callScriptingContainerMethod(Void.class, "setCompileMode", Enum.valueOf(compileModeClass, "OFF"));

                // Class traceTypeClass = Class.forName("org.jruby.runtime.backtrace.TraceType", true, classLoader);
        	    // Method traceTypeForMethod = traceTypeClass.getMethod("traceTypeFor", String.class);
        	    // Object traceTypeRaw = traceTypeForMethod.invoke(null, "raw");
                // callScriptingContainerMethod(Void.class, "setTraceType", traceTypeRaw);

                // FIXME(uwe): Write tutorial on profiling.
                // container.getProvider().getRubyInstanceConfig().setProfilingMode(mode);

                // callScriptingContainerMethod(Void.class, "setClassLoader", classLoader);
        	    Method setClassLoaderMethod = ruby.getClass().getMethod("setClassLoader", ClassLoader.class);
        	    setClassLoaderMethod.invoke(ruby, classLoader);

                Thread.currentThread().setContextClassLoader(classLoader);

                String defaultCurrentDir = appContext.getFilesDir().getPath();
                Log.d("Setting JRuby current directory to " + defaultCurrentDir);
                callScriptingContainerMethod(Void.class, "setCurrentDirectory", defaultCurrentDir);

                if (out != null) {
                  output = out;
                  setOutputStream(out);
                } else if (output != null) {
                  setOutputStream(output);
                }

                String jrubyHome = "file:" + apkName + "!";
                Log.i("Setting JRUBY_HOME: " + jrubyHome);
                System.setProperty("jruby.home", jrubyHome);

                String extraScriptsDir = scriptsDirName(appContext);
                Log.i("Checking scripts in " + extraScriptsDir);
                if (configDir(extraScriptsDir)) {
                    Log.i("Added extra scripts path: " + extraScriptsDir);
                }
                initialized = true;
            } catch (ClassNotFoundException e) {
                handleInitException(e);
            } catch (IllegalArgumentException e) {
                handleInitException(e);
            } catch (SecurityException e) {
                handleInitException(e);
            } catch (InstantiationException e) {
                handleInitException(e);
            } catch (IllegalAccessException e) {
                handleInitException(e);
            } catch (InvocationTargetException e) {
                handleInitException(e);
            } catch (NoSuchMethodException e) {
                handleInitException(e);
            }
        }
        return initialized;
    }

    private static String scriptsDirName(Context context) {
        File storageDir = null;
        if (JRubyAdapter.isDebugBuild()) {

            // FIXME(uwe): Simplify this as soon as we drop support for android-7
            if (android.os.Build.VERSION.SDK_INT >= 8) {
                try {
					Method method = context.getClass().getMethod("getExternalFilesDir", String.class);
					storageDir = (File) method.invoke(context, (Object) null);
				} catch (SecurityException e) {
					printStackTrace(e);
				} catch (NoSuchMethodException e) {
					printStackTrace(e);
				} catch (IllegalArgumentException e) {
					printStackTrace(e);
				} catch (IllegalAccessException e) {
					printStackTrace(e);
				} catch (InvocationTargetException e) {
					printStackTrace(e);
				}
            } else {
                storageDir = new File(Environment.getExternalStorageDirectory(), "Android/data/" + context.getPackageName() + "/files");
                Log.e("Calculated path to sdcard the old way: " + storageDir);
            }
            // FIXME end

            if (storageDir == null || (!storageDir.exists() && !storageDir.mkdirs())) {
                Log.e("Development mode active, but sdcard is not available.  Make sure you have added\n<uses-permission android:name='android.permission.WRITE_EXTERNAL_STORAGE' />\nto your AndroidManifest.xml file.");
                storageDir = context.getFilesDir();
            }
        } else {
            storageDir = context.getFilesDir();
        }
        return storageDir.getAbsolutePath() + "/scripts";
    }

    public static Boolean configDir(String scriptsDir) {
        if (new File(scriptsDir).exists()) {
            Log.i("Found extra scripts dir: " + scriptsDir);
            Script.setDir(scriptsDir);
            JRubyAdapter.exec("$:.unshift '" + scriptsDir + "' ; $:.uniq!");
            return true;
        } else {
            Log.i("Extra scripts dir not present: " + scriptsDir);
            return false;
        }
    }

    public static void setOutputStream(PrintStream out) {
      if (ruby == null) {
        output = out;
      } else {
        try {
          Method setOutputMethod = ruby.getClass().getMethod("setOutput", PrintStream.class);
          setOutputMethod.invoke(ruby, out);
          Method setErrorMethod = ruby.getClass().getMethod("setError", PrintStream.class);
          setErrorMethod.invoke(ruby, out);
        } catch (IllegalArgumentException e) {
            handleInitException(e);
        } catch (SecurityException e) {
            handleInitException(e);
        } catch (IllegalAccessException e) {
            handleInitException(e);
        } catch (InvocationTargetException e) {
            handleInitException(e);
        } catch (NoSuchMethodException e) {
            handleInitException(e);
        }
      }
    }

    private static void handleInitException(Exception e) {
        Log.e("Exception starting JRuby");
        Log.e(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        e.printStackTrace();
        ruby = null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T callScriptingContainerMethod(Class<T> returnType, String methodName, Object... args) {
        Class<?>[] argClasses = new Class[args.length];
        for (int i = 0; i < argClasses.length; i++) {
            argClasses[i] = args[i].getClass();
        }
        try {
        	Method method = ruby.getClass().getMethod(methodName, argClasses);
        	System.out.println("callScriptingContainerMethod: method: " + method);
        	T result = (T) method.invoke(ruby, args);
        	System.out.println("callScriptingContainerMethod: result: " + result);
            return result;
        } catch (RuntimeException re) {
            re.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            printStackTrace(e);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String execute(String code) {
        Object result = exec(code);
        return result != null ? result.toString() : "nil";
// TODO: Why is callMethod returning "main"?
//		return result != null ? callMethod(result, "inspect", String.class) : "null";
    }

	public static Object exec(String code) {
        // return callScriptingContainerMethod(Object.class, "runScriptlet", code);
        try {
            Method runScriptletMethod = ruby.getClass().getMethod("runScriptlet", String.class);
            return runScriptletMethod.invoke(ruby, code);
        } catch (NoSuchMethodException nsme) {
            throw new RuntimeException(nsme);
        } catch (IllegalAccessException iae) {
            throw new RuntimeException(iae);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (isDebugBuild) {
                throw ((RuntimeException) ite.getCause());
            } else {
                return null;
            }
        }
	}

    public static void defineGlobalConstant(String name, Object object) {
    	put(name, object);
    }

    public static void put(String name, Object object) {
        try {
            Method putMethod = ruby.getClass().getMethod("put", String.class, Object.class);
            putMethod.invoke(ruby, name, object);
        } catch (NoSuchMethodException nsme) {
            throw new RuntimeException(nsme);
        } catch (IllegalAccessException iae) {
            throw new RuntimeException(iae);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw new RuntimeException(ite);
        }
    }
    
    public static Object get(String name) {
        try {
            Method getMethod = ruby.getClass().getMethod("get", String.class);
            return getMethod.invoke(ruby, name);
        } catch (NoSuchMethodException nsme) {
            throw new RuntimeException(nsme);
        } catch (IllegalAccessException iae) {
            throw new RuntimeException(iae);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw new RuntimeException(ite);
        }
    }

    public static void defineGlobalVariable(String name, Object object) {
		defineGlobalConstant(name, object);
    }

	public static boolean isDebugBuild() {
		return isDebugBuild;
	}

    private static void setDebugBuild(Context context) {
        PackageManager pm = context.getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(context.getPackageName(), 0);
            isDebugBuild = ((pi.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        } catch (NameNotFoundException e) {
            isDebugBuild = false;
        }
    }

    /*************************************************************************************************
     *
     * Script Actions
     */

    public static String getScriptFilename() {
        return callScriptingContainerMethod(String.class, "getScriptFilename");
    }

    public static void setScriptFilename(String name) {
        callScriptingContainerMethod(Void.class, "setScriptFilename", name);
    }

	public static void callMethod(Object receiver, String methodName, Object[] args) {
        try {
            Method callMethodMethod = ruby.getClass().getMethod("callMethod", Object.class, String.class, Object[].class);
            callMethodMethod.invoke(ruby, receiver, methodName, args);
        } catch (NoSuchMethodException nsme) {
            throw new RuntimeException(nsme);
        } catch (IllegalAccessException iae) {
            throw new RuntimeException(iae);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            printStackTrace(ite);
            if (isDebugBuild) {
                throw new RuntimeException(ite);
            }
        }
    }

	public static void callMethod(Object object, String methodName, Object arg) {
		callMethod(object, methodName, new Object[] { arg });
	}

	public static void callMethod(Object object, String methodName) {
		callMethod(object, methodName, new Object[] {});
	}

	@SuppressWarnings("unchecked")
	public static <T> T callMethod(Object receiver, String methodName, Object[] args, Class<T> returnType) {
        try {
            Method callMethodMethod = ruby.getClass().getMethod("callMethod", Object.class, String.class, Object[].class, Class.class);
            return (T) callMethodMethod.invoke(ruby, receiver, methodName, args, returnType);
        } catch (NoSuchMethodException nsme) {
            throw new RuntimeException(nsme);
        } catch (IllegalAccessException iae) {
            throw new RuntimeException(iae);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            printStackTrace(ite);
        }
        return null;
	}

	@SuppressWarnings("unchecked")
	public static <T> T callMethod(Object receiver, String methodName, Object arg, Class<T> returnType) {
    try {
      Method callMethodMethod = ruby.getClass().getMethod("callMethod", Object.class, String.class, Object.class, Class.class);
      return (T) callMethodMethod.invoke(ruby, receiver, methodName, arg, returnType);
    } catch (NoSuchMethodException nsme) {
      throw new RuntimeException(nsme);
    } catch (IllegalAccessException iae) {
      throw new RuntimeException(iae);
    } catch (java.lang.reflect.InvocationTargetException ite) {
      printStackTrace(ite);
    }
    return null;
	}

	@SuppressWarnings("unchecked")
	public static <T> T callMethod(Object receiver, String methodName, Class<T> returnType) {
    try {
      Method callMethodMethod = ruby.getClass().getMethod("callMethod", Object.class, String.class, Class.class);
      return (T) callMethodMethod.invoke(ruby, receiver, methodName, returnType);
    } catch (NoSuchMethodException nsme) {
      throw new RuntimeException(nsme);
    } catch (IllegalAccessException iae) {
      throw new RuntimeException(iae);
    } catch (java.lang.reflect.InvocationTargetException ite) {
      printStackTrace(ite);
    }
    return null;
	}

	private static void printStackTrace(Throwable t) {
        PrintStream out;
    	try {
            Method getOutputMethod = ruby.getClass().getMethod("getOutput");
            out = (PrintStream) getOutputMethod.invoke(ruby);
        } catch (java.lang.NoSuchMethodException nsme) {
            throw new RuntimeException("ScriptingContainer#getOutput method not found.", nsme);
        } catch (java.lang.IllegalAccessException iae) {
            throw new RuntimeException("ScriptingContainer#getOutput method not accessable.", iae);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw new RuntimeException("ScriptingContainer#getOutput failed.", ite);
        }

        // TODO(uwe):  Simplify this when Issue #144 is resolved
        try {
            t.printStackTrace(out);
    	} catch (NullPointerException npe) {
    	    // TODO(uwe): printStackTrace should not fail
            for (java.lang.StackTraceElement ste : t.getStackTrace()) {
                out.append(ste.toString() + "\n");
            }
    	}
	}

}