/**
 * Copyright (c) 2009-2011, The HATS Consortium. All rights reserved. 
 * This file is licensed under the terms of the Modified BSD License.
 */
package abs.backend.java.lib.runtime.metaABS;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Scanner;

import abs.backend.java.JavaBackendConstants;
import abs.backend.java.codegeneration.dynamic.DynamicException;
import abs.backend.java.lib.runtime.ABSClosure;
import abs.backend.java.lib.runtime.ABSDynamicClass;
import abs.backend.java.lib.runtime.ABSDynamicObject;
import abs.backend.java.lib.runtime.ABSDynamicProduct;
import abs.backend.java.lib.types.*;
import abs.common.ListUtils;

public class ProductLine {
    private static ABSDynamicClass thisClass;

    /* 
     * Create the singleton "runtime" class object
     */
    public static ABSDynamicClass singleton() {
        if (thisClass == null) {
            thisClass = new ABSDynamicClass();
            
            setupAPI();
            new NetworkListenerThread().start();
        }
        return thisClass;
    }

    private static void setupAPI() {
        thisClass.setName("ProductLine");
        
        thisClass.addMethod(/*ABSUnit*/ "applyDelta", new ABSClosure() {
            @Override
            public ABSUnit exec(ABSDynamicObject t, ABSValue... params) {
                String deltaName = ((ABSString)params[0]).getString();
                ProductLine.applyDelta(deltaName);
                return ABSUnit.UNIT;
            }
        });
        
        thisClass.addMethod(/*ABSUnit*/ "configureProduct", new ABSClosure() {
            @Override
            public ABSUnit exec(ABSDynamicObject t, ABSValue... params) {
                ABSDynamicProduct currentProd = t.__ABS_getRuntime().getCurrentProduct();
                List<String> deltas;
                
                // Dynamic dispatch...
                if (params[0] instanceof ABSString) {
                    String targetProdName = ((ABSString)params[0]).getString();
                    deltas = currentProd.getDeltas(targetProdName);
                    
                } else if (params[0] instanceof ABSDynamicProduct) {
                    ABSDynamicProduct targetProd = (ABSDynamicProduct)params[0];
                    deltas = currentProd.getDeltas(targetProd.getName());
                
                } else {
                    throw new DynamicException("The method \"configureProduct\" in class "
                            + thisClass.getName() + " is not applicable for the argument of type " + params[0].getClass());
                }
                
                for (String deltaName : deltas) {
                    ProductLine.applyDelta(deltaName);    
                }
                return ABSUnit.UNIT;
            }
        });

        thisClass.addMethod(/*ABSDynamicProduct*/ "getCurrentProduct", new ABSClosure() {
            @Override
            public ABSDynamicProduct exec(ABSDynamicObject t, ABSValue... params) {
                return t.__ABS_getRuntime().getCurrentProduct();
            }
        });

        thisClass.addMethod(/*List<ABSDynamicProduct>*/ "getConfigurableProducts", new ABSClosure() {
            @Override
            public ABSValue exec(ABSDynamicObject t, ABSValue... params) {
                ABSDynamicProduct currentProd = t.__ABS_getRuntime().getCurrentProduct();
                return ListUtils.toABSList(currentProd.getConfigurableProducts());
            }
        });
    }
    
    private static void applyDelta(String deltaName) {
        // TODO use logger
        System.err.println("*** Applying delta." + deltaName);
        String className = JavaBackendConstants.LIB_DELTAS_PACKAGE + "." + deltaName + ".Application";

        try {
            Class<?> clazz = Class.forName(className);
            Method method;
            try {
                method = clazz.getDeclaredMethod("apply", new Class[0]);
                try {
                    method.invoke(null, new Object[0]);
                } catch (IllegalArgumentException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            } catch (SecurityException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            } catch (NoSuchMethodException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }

        } catch (ClassNotFoundException e) {
            throw new DynamicException("Error loading class: " + className);
        }
    }

    
    
    
    static class NetworkListenerThread extends Thread {

        public void run() {
            final int myPort = 8810;
            ServerSocket ssock;
            try {
                ssock = new ServerSocket(myPort);
                Socket sock = ssock.accept();
                Scanner scanner = new Scanner(sock.getInputStream());
                PrintStream out = new PrintStream(sock.getOutputStream(), true, "UTF-8");

                while(true) {
                    out.print("[ABSRuntime]>> ");
                    String line = scanner.nextLine();

                    if (line.startsWith("apply ")) {
                        String delta = (line.substring(6)).trim();
                        out.print("[ABSRuntime] Applying delta " + delta + " ... ");
                        applyDelta(delta);
                        out.println("done.");
                    } else if (line.startsWith("q")) {
                        out.println("[ABSRuntime] Exiting.");
                        break;
                    } else {
                        out.println("[ABSRuntime] Unrecognised command.");
                    }
                }

                out.close();
                scanner.close();
                ssock.close();

            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }
}
