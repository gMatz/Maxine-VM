/*
 * Copyright (c) 2010 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.cri.ci;

import static java.lang.reflect.Modifier.*;

import java.lang.reflect.*;
import java.util.*;

import com.sun.cri.ri.*;

/**
 * Miscellaneous collection of utility methods used in the {@code CRI} project.
 * This methods may also be used by the compiler and runtime classes.
 * 
 * @author Doug Simon
 */
public class CiUtil {
    
    /**
     * Extends the functionality of {@link Class#getSimpleName()} to include a non-empty string for anonymous and local
     * classes.
     * 
     * @param clazz the class for which the simple name is being requested
     * @param withEnclosingClass specifies if the returned name should be qualified with the name(s) of the enclosing
     *            class/classes of {@code clazz} (if any). This option is ignored if {@code clazz} denotes an anonymous
     *            or local class.
     * @return the simple name
     */
    public static String getSimpleName(Class<?> clazz, boolean withEnclosingClass) {
        final String simpleName = clazz.getSimpleName();
        if (simpleName.length() != 0) {
            if (withEnclosingClass) {
                String prefix = "";
                Class<?> enclosingClass = clazz;
                while ((enclosingClass = enclosingClass.getEnclosingClass()) != null) {
                    prefix = prefix + enclosingClass.getSimpleName() + ".";
                }
                return prefix + simpleName;
            }
            return simpleName;
        }
        // Must be an anonymous or local class
        final String name = clazz.getName();
        int index = name.indexOf('$');
        if (index == -1) {
            return name;
        }
        index = name.lastIndexOf('.', index);
        if (index == -1) {
            return name;
        }
        return name.substring(index + 1);
    }

    public static final int K = 1024;
    public static final int M = 1024 * 1024;
    public static boolean isOdd(int n) {
        return (n & 1) == 1;
    }
    public static boolean isEven(int n) {
        return (n & 1) == 0;
    }
    /**
     * Checks whether the specified integer is a power of two.
     *
     * @param val the value to check
     * @return {@code true} if the value is a power of two; {@code false} otherwise
     */
    public static boolean isPowerOf2(int val) {
        return val != 0 && (val & val - 1) == 0;
    }
    /**
     * Checks whether the specified long is a power of two.
     *
     * @param val the value to check
     * @return {@code true} if the value is a power of two; {@code false} otherwise
     */
    public static boolean isPowerOf2(long val) {
        return val != 0 && (val & val - 1) == 0;
    }
    /**
     * Computes the log (base 2) of the specified integer, rounding down.
     * (E.g {@code log2(8) = 3}, {@code log2(21) = 4})
     *
     * @param val the value
     * @return the log base 2 of the value
     */
    public static int log2(int val) {
        assert val > 0 && isPowerOf2(val);
        return 31 - Integer.numberOfLeadingZeros(val);
    }
    /**
     * Computes the log (base 2) of the specified long, rounding down.
     * (E.g {@code log2(8) = 3}, {@code log2(21) = 4})
     *
     * @param val the value
     * @return the log base 2 of the value
     */
    public static int log2(long val) {
        assert val > 0 && isPowerOf2(val);
        return 63 - Long.numberOfLeadingZeros(val);
    }
    public static int align(int size, int align) {
        assert isPowerOf2(align);
        return (size + align - 1) & ~(align - 1);
    }
    /**
     * Gets a word with the nth bit set.
     * @param n the nth bit to set
     * @return an integer value with the nth bit set
     */
    public static int nthBit(int n) {
        return (n >= Integer.SIZE ? 0 : 1 << (n));
    }
    /**
     * Gets a word with the right-most n bits set.
     * @param n the number of right most bits to set
     * @return an integer value with the right-most n bits set
     */
    public static int rightNBits(int n) {
        return nthBit(n) - 1;
    }
    /**
     * Converts a given type to its Java programming language name. The following are examples of strings returned by
     * this method:
     *
     * <pre>
     *     qualified == true:
     *         java.lang.Object
     *         int
     *         boolean[][]
     *     qualified == false:
     *         Object
     *         int
     *         boolean[][]
     * </pre>
     *
     * @param riType the type to be converted to a Java name
     * @param qualified specifies if the package prefix of the type should be included in the returned name
     * @return the Java name corresponding to {@code riType}
     */
    public static String toJavaName(RiType riType, boolean qualified) {
        CiKind kind = riType.kind();
        if (kind.isPrimitive() || kind == CiKind.Void) {
            return kind.javaName;
        }
        return internalNameToJava(riType.name(), qualified);
    }
    /**
     * Converts a given type to its Java programming language name. The following are examples of strings returned by
     * this method:
     *
     * <pre>
     *      java.lang.Object
     *      int
     *      boolean[][]
     * </pre>
     *
     * @param riType the type to be converted to a Java name
     * @return the Java name corresponding to {@code riType}
     */
    public static String toJavaName(RiType riType) {
        return internalNameToJava(riType.name(), true);
    }
    
    public static String internalNameToJava(String name, boolean qualified) {
        switch (name.charAt(0)) {
            case 'L': {
                String result = name.substring(1, name.length() - 1).replace('/', '.');
                if (!qualified) {
                    final int lastDot = result.lastIndexOf('.');
                    if (lastDot != -1) {
                        result = result.substring(lastDot + 1);
                    }
                }
                return result;

            }
            case '[':
                return internalNameToJava(name.substring(1), qualified) + "[]";
            default:
                if (name.length() != 1) {
                    throw new IllegalArgumentException("Illegal internal name: " + name);
                }
                return CiKind.fromPrimitiveOrVoidTypeChar(name.charAt(0)).javaName;
        }
    }
    /**
     * Gets a string for a given method formatted according to a given format specification. A format specification is
     * composed of characters that are to be copied verbatim to the result and specifiers that denote an attribute of
     * the method that is to be copied to the result. A specifier is a single character preceded by a '%' character. The
     * accepted specifiers and the method attributes they denote are described below:
     *
     * <pre>
     *     Specifier | Description                                          | Example(s)
     *     ----------+------------------------------------------------------------------------------------------
     *     'R'       | Qualified return type                                | "int" "java.lang.String"
     *     'r'       | Unqualified return type                              | "int" "String"
     *     'H'       | Qualified holder                                     | "java.util.Map.Entry"
     *     'h'       | Unqualified holder                                   | "Entry"
     *     'n'       | Method name                                          | "add"
     *     'P'       | Qualified parameter types, separated by ', '         | "int, java.lang.String"
     *     'p'       | Unqualified parameter types, separated by ', '       | "int, String"
     *     'f'       | Indicator if method is unresolved, static or virtual | "unresolved" "static" "virtual"
     *     '%'       | A '%' character                                      | "%"
     * </pre>
     *
     * @param format a format specification
     * @param method the method to be formatted
     * @param kinds if {@code true} then the types in {@code method}'s signature are printed in the
     *            {@linkplain CiKind#jniName JNI} form of their {@linkplain CiKind kind}
     * @return the result of formatting this method according to {@code format}
     * @throws IllegalFormatException if an illegal specifier is encountered in {@code format}
     */
    public static String format(String format, RiMethod method, boolean kinds) throws IllegalFormatException {
        final StringBuilder sb = new StringBuilder();
        int index = 0;
        RiSignature sig = method.signature();
        while (index < format.length()) {
            final char ch = format.charAt(index++);
            if (ch == '%') {
                if (index >= format.length()) {
                    throw new UnknownFormatConversionException("An unquoted '%' character cannot terminate a method format specification");
                }
                final char specifier = format.charAt(index++);
                boolean qualified = false;
                switch (specifier) {
                    case 'R':
                        qualified = true;
                        // fall through
                    case 'r': {
                        sb.append(kinds ? sig.returnKind().jniName : toJavaName(sig.returnType(null), qualified));
                        break;
                    }
                    case 'H':
                        qualified = true;
                        // fall through
                    case 'h': {
                        sb.append(toJavaName(method.holder(), qualified));
                        break;
                    }
                    case 'n': {
                        sb.append(method.name());
                        break;
                    }
                    case 'P':
                        qualified = true;
                        // fall through
                    case 'p': {
                        for (int i = 0; i < sig.argumentCount(false); i++) {
                            if (i != 0) {
                                sb.append(", ");
                            }
                            sb.append(kinds ? sig.argumentKindAt(i).jniName : toJavaName(sig.argumentTypeAt(i, null), qualified));
                        }
                        break;
                    }
                    case 'f': {
                        sb.append(!method.isResolved() ? "unresolved" : isStatic(method.accessFlags()) ? "static" : "virtual");
                        break;
                    }
                    case '%': {
                        sb.append('%');
                        break;
                    }
                    default: {
                        throw new UnknownFormatConversionException(String.valueOf(specifier));
                    }
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
    /**
     * Gets a string for a given field formatted according to a given format specification. A format specification is
     * composed of characters that are to be copied verbatim to the result and specifiers that denote an attribute of
     * the field that is to be copied to the result. A specifier is a single character preceded by a '%' character. The
     * accepted specifiers and the field attributes they denote are described below:
     *
     * <pre>
     *     Specifier | Description                                          | Example(s)
     *     ----------+------------------------------------------------------------------------------------------
     *     'T'       | Qualified type                                       | "int" "java.lang.String"
     *     't'       | Unqualified type                                     | "int" "String"
     *     'H'       | Qualified holder                                     | "java.util.Map.Entry"
     *     'h'       | Unqualified holder                                   | "Entry"
     *     'n'       | Field name                                           | "age"
     *     'f'       | Indicator if field is unresolved, static or instance | "unresolved" "static" "instance"
     *     '%'       | A '%' character                                      | "%"
     * </pre>
     *
     * @param format a format specification
     * @param field the field to be formatted
     * @param kinds if {@code true} then {@code field}'s type is printed in the
     *            {@linkplain CiKind#jniName JNI} form of its {@linkplain CiKind kind}
     * @return the result of formatting this field according to {@code format}
     * @throws IllegalFormatException if an illegal specifier is encountered in {@code format}
     */
    public static String format(String format, RiField field, boolean kinds) throws IllegalFormatException {
        final StringBuilder sb = new StringBuilder();
        int index = 0;
        RiType type = field.type();
        while (index < format.length()) {
            final char ch = format.charAt(index++);
            if (ch == '%') {
                if (index >= format.length()) {
                    throw new UnknownFormatConversionException("An unquoted '%' character cannot terminate a field format specification");
                }
                final char specifier = format.charAt(index++);
                boolean qualified = false;
                switch (specifier) {
                    case 'T':
                        qualified = true;
                        // fall through
                    case 't': {
                        sb.append(kinds ? type.kind().jniName : toJavaName(type, qualified));
                        break;
                    }
                    case 'H':
                        qualified = true;
                        // fall through
                    case 'h': {
                        sb.append(toJavaName(field.holder(), qualified));
                        break;
                    }
                    case 'n': {
                        sb.append(field.name());
                        break;
                    }
                    case 'f': {
                        sb.append(!field.isResolved() ? "unresolved" : isStatic(field.accessFlags()) ? "static" : "instance");
                        break;
                    }
                    case '%': {
                        sb.append('%');
                        break;
                    }
                    default: {
                        throw new UnknownFormatConversionException(String.valueOf(specifier));
                    }
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
    /**
     * Converts a Java source-language class name into the internal form.
     *
     * @param className the class name
     * @return the internal name form of the class name
     */
    public static String toInternalName(String className) {
        return "L" + className.replace('.', '/') + ";";
    }
    
    private static final Object[] NO_ARGUMENTS = {};

    /**
     * Invokes a given Java method via reflection.
     * 
     * @param method the method to invoke
     * @param args the arguments to the invocation
     * @return the result of the invocation
     */
    public static CiConstant invoke(Method method, CiMethodInvokeArguments args) throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {
        method.setAccessible(true);
        // build the argument list
        Object recvr = null;
        if (!isStatic(method.getModifiers())) {
            recvr = args.nextArg().asObject();
        }
        ArrayList<Object> argList = new ArrayList<Object>(method.getParameterTypes().length);
        for (CiConstant arg = args.nextArg(); arg != null; arg = args.nextArg()) {
            argList.add(arg.boxedValue());
        }
        // attempt to invoke the method
        Object result = method.invoke(recvr, argList.toArray());
        CiKind kind = CiKind.fromJavaClass(method.getReturnType());
        return CiConstant.forBoxed(kind, result);
    }
    
    /**
     * Creates a set that uses reference-equality instead of {@link Object#equals(Object)}
     * when comparing values.
     * 
     * @param <T> the type of elements in the set
     * @return a set based on reference-equality
     */
    public static <T> Set<T> newIdentityHashSet() {
        return Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
    }
}