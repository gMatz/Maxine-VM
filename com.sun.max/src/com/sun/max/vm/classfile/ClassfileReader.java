/*
 * Copyright (c) 2017-2019, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package com.sun.max.vm.classfile;

import static com.sun.max.annotate.LOCAL_SUBSTITUTION.Static.*;
import static com.sun.max.vm.VMOptions.*;
import static com.sun.max.vm.actor.Actor.*;
import static com.sun.max.vm.actor.holder.ClassActor.*;
import static com.sun.max.vm.actor.holder.ClassActorFactory.*;
import static com.sun.max.vm.actor.member.MethodActor.*;
import static com.sun.max.vm.classfile.ErrorContext.*;
import static com.sun.max.vm.type.ClassRegistry.Property.*;
import static com.sun.max.vm.type.JavaTypeDescriptor.*;

import java.io.*;
import java.lang.annotation.*;
import java.lang.instrument.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.lang.*;
import com.sun.max.platform.*;
import com.sun.max.program.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.ClassfileWriter.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.hosted.*;
import com.sun.max.vm.instrument.*;
import com.sun.max.vm.intrinsics.*;
import com.sun.max.vm.jdk.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.ti.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.type.ClassRegistry.*;
import com.sun.max.vm.value.*;

import sun.reflect.CallerSensitive;

/**
 * Reads a class file to create a corresponding {@link ClassActor}.
 */
public final class ClassfileReader {

    public static final char JAVA_MIN_SUPPORTED_VERSION = 45;
    public static final char JAVA_1_5_VERSION = 49;
    public static final char JAVA_6_VERSION = 50;
    public static final char JAVA_7_VERSION = 51;
    public static final char JAVA_8_VERSION = 52;
    public static final char JAVA_MAX_SUPPORTED_VERSION =
        (JDK.JDK_VERSION == JDK.JDK_8) ?
        JAVA_8_VERSION :
        JAVA_7_VERSION;
    public static final char JAVA_MAX_SUPPORTED_MINOR_VERSION = 0;

    protected final ClassfileStream classfileStream;
    protected final ClassLoader classLoader;
    protected final ClassRegistry classRegistry;
    protected ConstantPool constantPool;
    protected int majorVersion;
    protected TypeDescriptor classOuterClass;
    protected TypeDescriptor[] classInnerClasses;
    protected TypeDescriptor classDescriptor;
    protected int classFlags;

    public ClassfileReader(ClassfileStream classfileStream, ClassLoader classLoader) {
        this.classfileStream = classfileStream;
        this.classLoader = classLoader;
        this.classRegistry = ClassRegistry.makeRegistry(classLoader);
    }

    /**
     * A utility class for efficiently determining that a sequence of fields or methods
     * are unique with respect to their names and signatures.
     */
    static class MemberSet extends ChainedHashMapping<MemberActor, MemberActor> {
        MemberSet(int initialCapacity) {
            super(initialCapacity);
        }
        @Override
        public boolean equivalent(MemberActor memberActor1, MemberActor memberActor2) {
            return memberActor1.matchesNameAndType(memberActor2.name, memberActor2.descriptor);
        }
        @Override
        public int hashCode(MemberActor memberActor) {
            return memberActor.name.hashCode() ^ memberActor.descriptor.hashCode();
        }

        /**
         * Adds a given member to this set.
         *
         * @param memberActor the member to add
         * @return the member actor currently in this set whose name and signature match {@code memberActor}. This will
         *         be null if there is no matching member currently in this set.
         */
        public MemberActor add(MemberActor memberActor) {
            return super.put(memberActor, memberActor);
        }
    }

    /**
     * Parses a Java identifier at a given offset of a string.
     *
     * @param string the string to test
     * @param offset the offset at which a legal field or type identifier should occur
     * @return the first character after the legal identifier or -1 if there is no legal identifier at {@code offset}
     */
    public static int parseIdentifier(String string, int offset) {
        boolean isFirstChar = true;
        char ch;
        int i;
        for (i = offset; i != string.length(); i++, isFirstChar = false) {
            ch = string.charAt(i);
            if (ch < 128) {
                // Quick check for ASCII
                if ((ch >= 'a' && ch <= 'z') ||
                    (ch >= 'A' && ch <= 'Z') ||
                    (ch == '_' || ch == '$') ||
                    (!isFirstChar && ch >= '0' && ch <= '9')) {
                    continue;
                }

                break;
            }
            if (Character.isJavaIdentifierStart(ch)) {
                continue;
            }
            if (!isFirstChar && Character.isJavaIdentifierPart(ch)) {
                continue;
            }
            break;
        }
        return isFirstChar ? -1 : i;
    }

    public static void verifyFieldName(Utf8Constant name) {
        if (!isValidFieldName(name)) {
            throw classFormatError("Invalid field name: " + name);
        }
    }

    public static void verifyMethodName(Utf8Constant name, boolean allowClinit) {
        if (!isValidMethodName(name, allowClinit)) {
            throw classFormatError("Invalid method name: " + name);
        }
    }

    public static boolean isValidFieldName(Utf8Constant name) {
        return parseIdentifier(name.string, 0) == name.string.length();
    }

    public static boolean isValidMethodName(Utf8Constant name, boolean allowClinit) {
        if (name.equals(SymbolTable.INIT)) {
            return true;
        }
        if (allowClinit && name.equals(SymbolTable.CLINIT)) {
            return true;
        }
        return parseIdentifier(name.string, 0) == name.string.length();
    }

    /**
     * Verifies that the class file version is supported.
     *
     * @param major the major version number
     * @param minor the minor version number
     */
    public static void verifyVersion(int major, int minor) {
        if ((major >= JAVA_MIN_SUPPORTED_VERSION) &&
            (major <= JAVA_MAX_SUPPORTED_VERSION) &&
            ((major != JAVA_MAX_SUPPORTED_VERSION) ||
             (minor <= JAVA_MAX_SUPPORTED_MINOR_VERSION))) {
            return;
        }
        throw classFormatError("Unsupported major.minor version " + major + "." + minor);
    }

    /**
     * Verifies that the flags for a class are valid.
     *
     * @param flags the flags to test
     * @return the flags the VM should use
     * @throws ClassFormatError if the flags are invalid
     */
    public static int verifyClassFlags(int flags, int major) {
        boolean valid;
        final int finalAndAbstract = ACC_FINAL | ACC_ABSTRACT;
        if ((flags & ACC_INTERFACE) != 0) {
            final int newFlags;
            if (major < JAVA_6_VERSION) {
                // Set abstract bit for old class files for backward compatibility
                newFlags = flags | ACC_ABSTRACT;
            } else {
                newFlags = flags;
            }

            // If the ACC_INTERFACE flag of this class file is set, its ACC_ABSTRACT flag must also be set (2.13.1) and
            // its ACC_PUBLIC flag may be set. Such a class file may not have any of the other flags in Table 4.1 set.
            valid = (newFlags & (finalAndAbstract | ACC_SUPER)) == ACC_ABSTRACT;
        } else {
            // If the ACC_INTERFACE flag of this class file is not set, it may have any of the other flags in Table 4.1
            // set. However, such a class file cannot have both its ACC_FINAL and ACC_ABSTRACT flags set (2.8.2).
            valid = (flags & finalAndAbstract) != finalAndAbstract;
        }
        if (valid) {
            return flags;
        }
        throw classFormatError("Invalid class flags 0x" + Integer.toHexString(flags));
    }

    /**
     * Verifies that the flags for a field are valid.
     *
     * @param name the name of the field
     * @param flags the flags to test
     * @param isInterface if the field flags are being tested for an interface
     * @throws ClassFormatError if the flags are invalid
     */
    public static void verifyFieldFlags(String name, int flags, boolean isInterface) {
        boolean valid;
        if (!isInterface) {
            // Class or instance fields
            final int maskedFlags = flags & (ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED);

            // Make sure that flags has at most one of its ACC_PRIVATE,
            // ACC_PROTECTED bits set. That is, do a population count of these
            // bit positions corresponding to these flags and ensure that it is
            // at most 1.
            valid = maskedFlags == 0 || (maskedFlags & ~(maskedFlags - 1)) == maskedFlags;

            // A field can't be both final and volatile
            final int finalAndVolatile = ACC_FINAL | ACC_VOLATILE;
            valid = valid && ((flags & finalAndVolatile) != finalAndVolatile);
        } else {
            // interface fields must be public static final (i.e. constants), but may have ACC_SYNTHETIC set
            valid = (flags & ~ACC_SYNTHETIC) == (ACC_STATIC | ACC_FINAL | ACC_PUBLIC);
        }
        if (!valid) {
            throw classFormatError(name + ": invalid field flags 0x" + Integer.toHexString(flags));
        }
    }

    /**
     * Verifies that the flags for a method are valid.
     *
     * @param flags the flags to test
     * @param isInit true if the method is "<init>"
     * @param isClinit true if the method is "<clinit>"
     * @param majorVersion the version of the class file
     * @throws ClassFormatError if the flags are invalid
     */
    public static void verifyMethodFlags(int flags, boolean isInterface, boolean isInit, boolean isClinit, int majorVersion) {
        // The checks below are based on JVMS8 4.6

        // Class and interface initialization methods are called
        // implicitly by the Java Virtual Machine. The value of their
        // access_flags item is ignored except for the setting of the
        // ACC_STRICT flag.
        if (isClinit) {
            return;
        }

        boolean valid = true;

        // If a method of a class or interface has its ACC_ABSTRACT flag set, it
        // must not have any of its ACC_PRIVATE, ACC_STATIC, ACC_FINAL,
        // ACC_SYNCHRONIZED, ACC_NATIVE, or ACC_STRICT flags set.
        if ((flags & ACC_ABSTRACT) != 0) {
            valid &= (flags & (ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_NATIVE)) == 0;
            if (majorVersion >= JAVA_1_5_VERSION) {
                valid &= (flags & (ACC_SYNCHRONIZED | ACC_STRICT)) == 0;
            }
        }

        if (valid) {
            if (!isInterface) {
                // Methods of classes may have any of the flags in Table 4.6-A
                // set. However, each method of a class may have at most one of
                // its ACC_PUBLIC, ACC_PRIVATE, and ACC_PROTECTED flags set

                // These are all small bits.  The value is between 0 and 7.
                final int maskedFlags = flags & (ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED);
                valid &= maskedFlags == 0 || (maskedFlags & ~(maskedFlags - 1)) == maskedFlags;
            } else {
                // Methods of interfaces may have any of the flags in Table
                // 4.6-A set except ACC_PROTECTED, ACC_FINAL, ACC_SYNCHRONIZED,
                // and ACC_NATIVE
                valid &= (flags & (ACC_PROTECTED | ACC_FINAL | ACC_SYNCHRONIZED | ACC_NATIVE)) == 0;

                // In a class file whose version number is less than 52.0, each
                // method of an interface must have its ACC_PUBLIC and
                // ACC_ABSTRACT flags set; in a class file whose version number
                // is 52.0 or above, each method of an interface must have
                // exactly one of its ACC_PUBLIC and ACC_PRIVATE flags set.
                if (majorVersion < JAVA_8_VERSION) {
                    final int publicAndAbstract = ACC_PUBLIC | ACC_ABSTRACT;
                    valid &= (flags & publicAndAbstract) == publicAndAbstract;
                    // And since it is public it can't be private as well
                    valid &= (flags & ACC_PRIVATE) == 0;
                } else {
                    final int maskedFlags = flags & (ACC_PUBLIC | ACC_PRIVATE);
                    valid &= (maskedFlags & ~(maskedFlags - 1)) == maskedFlags;
                }
            }

            if (valid) {
                if (isInit) {
                    // Each instance initialization method may have at most one of
                    // its ACC_PUBLIC, ACC_PRIVATE, and ACC_PROTECTED flags set
                    // (this part is already checked above), and may also have its
                    // ACC_VARARGS, ACC_STRICT, and ACC_SYNTHETIC flags set, but
                    // must not have any of the other flags in Table 4.6-A set.
                    valid = (flags & ~(ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED | ACC_VARARGS | ACC_STRICT | ACC_SYNTHETIC)) == 0;
                }
            }
        }
        if (!valid) {
            throw classFormatError("Invalid method flags 0x" + Integer.toHexString(flags));
        }
    }

    protected void readMagic() {
        final int magic = classfileStream.readInt();
        if (magic != 0xcafebabe) {
            throw classFormatError("Invalid magic number 0x" + Integer.toHexString(magic));
        }
    }

    protected InterfaceActor resolveInterface(int index) {
        final ClassConstant classConstant = constantPool.classAt(index, "interface name");
        try {
            return (InterfaceActor) classConstant.resolve(constantPool, index);
        } catch (ClassCastException classCastException) {
            throw incompatibleClassChangeError(classConstant.typeDescriptor().toJavaString() + " is not an interface");
        }
    }

    protected InterfaceActor[] readInterfaces() {
        final int nInterfaces = classfileStream.readUnsigned2();
        if (nInterfaces == 0) {
            return ClassActor.NO_INTERFACES;
        }
        final InterfaceActor[] interfaceActors = new InterfaceActor[nInterfaces];
        for (int i = 0; i < nInterfaces; i++) {
            interfaceActors[i] = resolveInterface(classfileStream.readUnsigned2());
        }
        return interfaceActors;
    }

    protected FieldActor[] readFields(boolean isInterface)  {
        final int nFields = classfileStream.readUnsigned2();
        if (nFields == 0) {
            // save time and space for classes that have no fields
            return ClassActor.NO_FIELDS;
        }
        final FieldActor[] fieldActors = new FieldActor[nFields];
        int nextFieldIndex = 0;

        final MemberSet fieldActorSet = new MemberSet(nFields);

    nextField:
        for (int i = 0; i < nFields; i++) {
            int flags = classfileStream.readUnsigned2();
            final int nameIndex = classfileStream.readUnsigned2();
            final Utf8Constant name = constantPool.utf8ConstantAt(nameIndex, "field name");
            verifyFieldName(name);

            final boolean isStatic = Actor.isStatic(flags);

            try {
                enterContext(new Object() {
                    @Override
                    public String toString() {
                        return "parsing field \"" + name + "\"";
                    }
                });
                final int descriptorIndex = classfileStream.readUnsigned2();
                final TypeDescriptor descriptor = parseTypeDescriptor(constantPool.utf8At(descriptorIndex, "field descriptor").toString());
                verifyFieldFlags(name.toString(), flags, isInterface);

                char constantValueIndex = 0;
                byte[] runtimeVisibleAnnotationsBytes = NO_RUNTIME_VISIBLE_ANNOTATION_BYTES;
                Utf8Constant genericSignature = NO_GENERIC_SIGNATURE;

                int nAttributes = classfileStream.readUnsigned2();
                while (nAttributes-- != 0) {
                    final int attributeNameIndex = classfileStream.readUnsigned2();
                    final String attributeName = constantPool.utf8At(attributeNameIndex, "attribute name").toString();
                    final int attributeSize = classfileStream.readSize4();
                    final int startPosition = classfileStream.getPosition();
                    if (isStatic && attributeName.equals("ConstantValue")) {
                        if (constantValueIndex != 0) {
                            throw classFormatError("Duplicate ConstantValue attribute");
                        }
                        constantValueIndex = (char) classfileStream.readUnsigned2();
                        if (constantValueIndex == 0) {
                            throw classFormatError("Invalid ConstantValue index");
                        }
                    } else if (attributeName.equals("Deprecated")) {
                        flags |= Actor.DEPRECATED;
                    } else if (attributeName.equals("Synthetic")) {
                        flags |= Actor.ACC_SYNTHETIC;
                    } else if (attributeName.equals(MaxineFlags.NAME)) {
                        flags |= classfileStream.readSigned4();
                    } else if (majorVersion >= JAVA_1_5_VERSION) {
                        if (attributeName.equals("Signature")) {
                            genericSignature = constantPool.utf8At(classfileStream.readUnsigned2(), "signature index");
                        } else if (attributeName.equals("RuntimeVisibleAnnotations")) {
                            runtimeVisibleAnnotationsBytes = classfileStream.readByteArray(attributeSize);
                        } else {
                            classfileStream.skip(attributeSize);
                        }
                    } else {
                        classfileStream.skip(attributeSize);
                    }

                    if (attributeSize != classfileStream.getPosition() - startPosition) {
                        throw classFormatError("Invalid attribute_length for " + attributeName + " attribute");
                    }
                }

                if (MaxineVM.isHosted() && runtimeVisibleAnnotationsBytes != null) {
                    for (Annotation annotation : getAnnotations(name, descriptor)) {
                        if (annotation.annotationType() == HOSTED_ONLY.class) {
                            continue nextField;
                        } else if (annotation.annotationType() == PLATFORM.class) {
                            if (!Platform.platform().isAcceptedBy((PLATFORM) annotation)) {
                                continue nextField;
                            }
                        } else if (annotation.annotationType() == JDK_VERSION.class) {
                            if (!JDK.thisVersionOrNewer((JDK_VERSION) annotation)) {
                                continue nextField;
                            }
                        } else if (annotation.annotationType() == RESET.class) {
                            assert !Actor.isFinal(flags) :
                                "A final field cannot have the RESET annotation: " + classDescriptor.toJavaString() + "." + name;
                        } else if (annotation.annotationType() == CONSTANT.class) {
                            flags |= CONSTANT;
                        } else if (annotation.annotationType() == CONSTANT_WHEN_NOT_ZERO.class) {
                            flags |= CONSTANT_WHEN_NOT_ZERO;
                        }

                    }
                }


                final Kind kind = descriptor.toKind();
                if (kind == Kind.VOID) {
                    throw classFormatError("Fields cannot be of type void");
                }
                final FieldActor fieldActor = new FieldActor(kind, name, descriptor, flags);

                if (constantValueIndex != 0) {
                    switch (fieldActor.kind.asEnum) {
                        case BYTE: {
                            classRegistry.set(CONSTANT_VALUE, fieldActor, ByteValue.from((byte) constantPool.intAt(constantValueIndex)));
                            break;
                        }
                        case BOOLEAN: {
                            classRegistry.set(CONSTANT_VALUE, fieldActor, BooleanValue.from(constantPool.intAt(constantValueIndex) != 0));
                            break;
                        }
                        case CHAR: {
                            classRegistry.set(CONSTANT_VALUE, fieldActor, CharValue.from((char) constantPool.intAt(constantValueIndex)));
                            break;
                        }
                        case SHORT: {
                            classRegistry.set(CONSTANT_VALUE, fieldActor, ShortValue.from((short) constantPool.intAt(constantValueIndex)));
                            break;
                        }
                        case INT: {
                            classRegistry.set(CONSTANT_VALUE, fieldActor, IntValue.from(constantPool.intAt(constantValueIndex)));
                            break;
                        }
                        case FLOAT: {
                            classRegistry.set(CONSTANT_VALUE, fieldActor, FloatValue.from(constantPool.floatAt(constantValueIndex)));
                            break;
                        }
                        case LONG: {
                            classRegistry.set(CONSTANT_VALUE, fieldActor, LongValue.from(constantPool.longAt(constantValueIndex)));
                            break;
                        }
                        case DOUBLE: {
                            classRegistry.set(CONSTANT_VALUE, fieldActor, DoubleValue.from(constantPool.doubleAt(constantValueIndex)));
                            break;
                        }
                        case REFERENCE: {
                            if (!descriptor.equals(STRING)) {
                                throw classFormatError("Invalid ConstantValue attribute");
                            }
                            classRegistry.set(CONSTANT_VALUE, fieldActor, ReferenceValue.from(constantPool.stringAt(constantValueIndex)));
                            break;
                        }
                        default: {
                            throw classFormatError("Cannot have ConstantValue for fields of type " + kind);
                        }
                    }
                }

                classRegistry.set(GENERIC_SIGNATURE, fieldActor, genericSignature);
                classRegistry.set(RUNTIME_VISIBLE_ANNOTATION_BYTES, fieldActor, runtimeVisibleAnnotationsBytes);

                // Check for duplicates
                if (fieldActorSet.add(fieldActor) != null) {
                    throw classFormatError("Duplicate field name and signature: " + name + " " + descriptor);
                }

                fieldActors[nextFieldIndex++] = fieldActor;
            } finally {
                exitContext();
            }
        }

        if (nextFieldIndex < nFields) {
            return Arrays.copyOf(fieldActors, nextFieldIndex);
        }
        return fieldActors;
    }

    protected TypeDescriptor[] readCheckedExceptionsAttribute() {
        final int nExceptionClasses = classfileStream.readUnsigned2();
        final TypeDescriptor[] checkedExceptions = new TypeDescriptor[nExceptionClasses];
        for (int i = 0; i < nExceptionClasses; i++) {
            final int checkedExceptionIndex = classfileStream.readUnsigned2();
            checkedExceptions[i] = constantPool.classAt(checkedExceptionIndex).typeDescriptor();
        }
        return checkedExceptions;
    }

    protected ExceptionHandlerEntry readExceptionHandlerEntry(int codeLength) {
        final int startBCI = classfileStream.readUnsigned2();
        final int endBCI = classfileStream.readUnsigned2();
        final int catchBCI = classfileStream.readUnsigned2();
        final int catchTypeCPI = classfileStream.readUnsigned2();

        if (startBCI >= codeLength || endBCI > codeLength || startBCI >= endBCI || catchBCI >= codeLength) {
            throw classFormatError("Invalid exception handler code range");
        }

        // Check the index and type of the catch type
        if (catchTypeCPI != 0) {
            constantPool.classAt(catchTypeCPI, "catch type in exception table");
        }

        return new ExceptionHandlerEntry(startBCI, endBCI, catchBCI, catchTypeCPI);
    }

    protected ExceptionHandlerEntry[] readExceptionHandlerTable(int codeLength) {
        final int nEntries = classfileStream.readUnsigned2();
        if (nEntries != 0) {
            final ExceptionHandlerEntry[] entries = new ExceptionHandlerEntry[nEntries];
            for (int i = 0; i < nEntries; i++) {
                entries[i] = readExceptionHandlerEntry(codeLength);
            }
            return entries;
        }
        return ExceptionHandlerEntry.NONE;
    }

    // CheckStyle: stop parameter assignment check
    protected Map<LocalVariableTable.Entry, LocalVariableTable.Entry> readLocalVariableTable(int maxLocals, int codeLength, Map<LocalVariableTable.Entry, LocalVariableTable.Entry> localVariableTableEntries, boolean forLVTT) {
        final int count = classfileStream.readUnsigned2();
        if (count == 0) {
            return localVariableTableEntries;
        }
        if (localVariableTableEntries == null) {
            localVariableTableEntries = new HashMap<LocalVariableTable.Entry, LocalVariableTable.Entry>(count);
        }
        for (int i = 0; i != count; ++i) {
            final LocalVariableTable.Entry entry = new LocalVariableTable.Entry(classfileStream, forLVTT);
            entry.verify(constantPool, codeLength, maxLocals, forLVTT);
            if (localVariableTableEntries.put(entry, entry) != null) {
                throw classFormatError("Duplicated " + (forLVTT ? "LocalVariableTypeTable" : "LocalVariableTable") + " entry at index " + i);
            }
        }
        return localVariableTableEntries;
    }
    // CheckStyle: resume parameter assignment check

    protected CodeAttribute readCodeAttribute(int methodAccessFlags) {
        final char maxStack = (char) classfileStream.readUnsigned2();
        final char maxLocals = (char) classfileStream.readUnsigned2();
        final int codeLength = classfileStream.readSize4();
        if (codeLength <= 0) {
            throw classFormatError("The value of code_length must be greater than 0");
        } else if (codeLength >= 0xFFFF) {
            throw classFormatError("Method code longer than 64 KB");
        }

        final byte[] code = classfileStream.readByteArray(codeLength);
        final ExceptionHandlerEntry[] exceptionHandlerTable = readExceptionHandlerTable(code.length);

        LineNumberTable lineNumberTable = LineNumberTable.EMPTY;
        LocalVariableTable localVariableTable = LocalVariableTable.EMPTY;
        Map<LocalVariableTable.Entry, LocalVariableTable.Entry> localVariableTableEntries = null;
        Map<LocalVariableTable.Entry, LocalVariableTable.Entry> localVariableTypeTableEntries = null;
        StackMapTable stackMapTable = null;

        int nAttributes = classfileStream.readUnsigned2();
        while (nAttributes-- != 0) {
            final int attributeNameIndex = classfileStream.readUnsigned2();
            final String attributeName = constantPool.utf8At(attributeNameIndex, "attribute name").toString();
            final int attributeSize = classfileStream.readSize4();
            final int startPosition = classfileStream.getPosition();
            if (attributeName.equals("LineNumberTable")) {
                lineNumberTable = new LineNumberTable(lineNumberTable, classfileStream, codeLength);
            } else if (attributeName.equals("StackMapTable")) {
                if (stackMapTable != null) {
                    throw classFormatError("Duplicate stack map attribute");
                }
                stackMapTable = new StackMapTable(classfileStream, constantPool, attributeSize);
            } else if (attributeName.equals("LocalVariableTable")) {
                localVariableTableEntries = readLocalVariableTable(maxLocals, codeLength, localVariableTableEntries, false);
            } else if (majorVersion >= JAVA_1_5_VERSION) {
                if (attributeName.equals("LocalVariableTypeTable")) {
                    localVariableTypeTableEntries = readLocalVariableTable(maxLocals, codeLength, localVariableTypeTableEntries, true);
                } else {
                    classfileStream.skip(attributeSize);
                }
            } else {
                classfileStream.skip(attributeSize);
            }

            if (attributeSize != classfileStream.getPosition() - startPosition) {
                throw classFormatError("Invalid attribute length for " + attributeName + " attribute");
            }
        }

        if (localVariableTypeTableEntries != null) {
            if (localVariableTableEntries == null) {
                throw classFormatError("LocalVariableTypeTable attribute present without LocalVariableTable");
            }
            for (LocalVariableTable.Entry lvttEntry : localVariableTypeTableEntries.values()) {
                final LocalVariableTable.Entry lvtEntry = localVariableTableEntries.get(lvttEntry);
                if (lvtEntry == null) {
                    throw classFormatError("LocalVariableTypeTable entry does not match any LocalVariableTable entry");
                }
                lvtEntry.copySignatureIndex(lvttEntry);
            }
        }

        if (localVariableTableEntries != null) {
            localVariableTable = new LocalVariableTable(localVariableTableEntries.values());
        }

        return new CodeAttribute(
                        constantPool,
                        code,
                        maxStack,
                        maxLocals,
                        exceptionHandlerTable,
                        lineNumberTable,
                        localVariableTable,
                        stackMapTable);
    }

    /**
     * Checks that a given method signature does not contain a {@linkplain Kind#REFERENCE reference} type.
     *
     * @param annotationClass the annotation applied to the method that enforces this constraint
     */
    protected void ensureSignatureIsPrimitive(SignatureDescriptor descriptor, Class annotationClass) {
        // Verify that there are no REFERENCE parameters
        for (int i = 0; i < descriptor.numberOfParameters(); ++i) {
            final Kind kind = descriptor.parameterDescriptorAt(i).toKind();
            ProgramError.check(kind != Kind.REFERENCE, annotationClass.getSimpleName() + " annotated methods cannot have reference parameters: " + descriptor);
        }
        ProgramError.check(descriptor.resultKind() != Kind.REFERENCE, annotationClass.getSimpleName() + " annotated methods cannot have reference return type: " + descriptor);
    }

    /**
     * Gets the declaration-like string for a field or method.
     *
     * @param name the name of the field or method
     * @param descriptor the type/signature of the field/method
     */
    private String memberString(Utf8Constant name, Descriptor descriptor) {
        if (descriptor instanceof TypeDescriptor) {
            // A field
            return classDescriptor.toJavaString() + "." + name + ' ' + ((TypeDescriptor) descriptor).toJavaString();
        }
        // A method
        return classDescriptor.toJavaString() + "." + name + ((SignatureDescriptor) descriptor).toJavaString(false, true);
    }

    @HOSTED_ONLY
    private Annotation[] getAnnotations(Utf8Constant name, Descriptor descriptor) {
        if (name == null) {
            try {
                Class holder = Classes.forName(classDescriptor.toJavaString(), false, ClassfileReader.class.getClassLoader());
                return holder.getAnnotations();
            } catch (NoClassDefFoundError | IllegalAccessError e) {
                // This occurs for synthesized classes that are not available on the class path
                return new Annotation[0];
            }
        }
        Class<?> holder = Classes.forName(classDescriptor.toJavaString(), false, ClassfileReader.class.getClassLoader());
        Annotation[] annotations;
        if (name.equals(SymbolTable.INIT)) {
            SignatureDescriptor sig = (SignatureDescriptor) descriptor;
            annotations = Classes.getDeclaredConstructor(holder, sig.resolveParameterTypes(ClassfileReader.class.getClassLoader())).getAnnotations();
        } else if (descriptor instanceof TypeDescriptor) {
            annotations = Classes.getDeclaredField(holder, name.string).getAnnotations();
        } else {
            SignatureDescriptor sig = (SignatureDescriptor) descriptor;
            try {
                annotations = holder.getDeclaredMethod(name.string, sig.resolveParameterTypes(ClassfileReader.class.getClassLoader())).getAnnotations();
            } catch (NoSuchMethodException noSuchMethodException) {
                Trace.line(1, "getAnnotations: method " + holder.getName() + "." + name.string + " is hidden to reflection");
                annotations = new Annotation[0];
            }
        }
        return annotations;
    }

    @HOSTED_ONLY
    public static final HashSet<Class<? extends Annotation>> bytecodeTemplateClasses = new HashSet<Class<? extends Annotation>>();

    @HOSTED_ONLY
    private static boolean isBytecodeTemplate(Class<? extends Annotation> anno) {
        return bytecodeTemplateClasses.contains(anno);
    }

    protected MethodActor[] readMethods(boolean isInterface) {
        final int numberOfMethods = classfileStream.readUnsigned2();
        if (numberOfMethods == 0) {
            return ClassActor.NO_METHODS;
        }
        final MethodActor[] methodActors = new MethodActor[numberOfMethods];
        int nextMethodIndex = 0;

        // A map for efficiently checking the uniqueness of methods
        final MemberSet methodActorSet = new MemberSet(numberOfMethods);

        int clinitIndex = -1;
    nextMethod:
        for (int i = 0; i < numberOfMethods; ++i) {
            int flags = classfileStream.readUnsigned2();
            final int nameIndex = classfileStream.readUnsigned2();
            Utf8Constant name = constantPool.utf8ConstantAt(nameIndex, "method name");
            verifyMethodName(name, true);

            int extraFlags = flags;
            boolean isClinit = false;
            boolean isInit = false;
            if (name.equals(SymbolTable.CLINIT)) {
                // Class and interface initialization methods (3.9) are called
                // implicitly by the Java virtual machine; the value of their
                // access_flags item is ignored except for the settings of the
                // ACC_STRICT flag.
                flags &= ACC_STRICT;
                flags |= ACC_STATIC;
                extraFlags = INITIALIZER | flags;
                isClinit = true;
            } else if (name.equals(SymbolTable.INIT)) {
                extraFlags |= INITIALIZER;
                isInit = true;
            }

            final boolean isStatic = Actor.isStatic(extraFlags);

            try {
                enterContext(new Object() {
                    @Override
                    public String toString() {
                        return "parsing method \"" + constantPool.utf8ConstantAt(nameIndex, "method name") + "\"";
                    }
                });

                verifyMethodFlags(flags, isInterface, isInit, isClinit, majorVersion);
                flags = extraFlags;

                final int descriptorIndex = classfileStream.readUnsigned2();
                final SignatureDescriptor descriptor = SignatureDescriptor.create(constantPool.utf8At(descriptorIndex, "method descriptor"));

                if (descriptor.computeNumberOfSlots() + (isStatic ? 0 : 1) > 255) {
                    throw classFormatError("Too many arguments in method signature: " + descriptor);
                }

                if (name.equals(SymbolTable.FINALIZE) && descriptor.equals(SignatureDescriptor.VOID) && (flags & ACC_STATIC) == 0) {
                    // this class has a finalizer method implementation
                    // (this bit will be cleared for java.lang.Object later)
                    classFlags |= FINALIZER;
                }

                CodeAttribute codeAttribute = null;
                TypeDescriptor[] checkedExceptions = NO_CHECKED_EXCEPTIONS;
                byte[] runtimeVisibleAnnotationsBytes = NO_RUNTIME_VISIBLE_ANNOTATION_BYTES;
                byte[] runtimeVisibleParameterAnnotationsBytes = NO_RUNTIME_VISIBLE_PARAMETER_ANNOTATION_BYTES;
                byte[] annotationDefaultBytes = NO_ANNOTATION_DEFAULT_BYTES;
                Utf8Constant genericSignature = NO_GENERIC_SIGNATURE;

                int nAttributes = classfileStream.readUnsigned2();
                while (nAttributes-- != 0) {
                    final int attributeNameIndex = classfileStream.readUnsigned2();
                    final String attributeName = constantPool.utf8At(attributeNameIndex, "attribute name").toString();
                    final int attributeSize = classfileStream.readSize4();
                    final int startPosition = classfileStream.getPosition();
                    if (attributeName.equals("Code")) {
                        if (codeAttribute != null) {
                            throw classFormatError("Duplicate Code attribute");
                        }
                        codeAttribute = readCodeAttribute(flags);
                    } else if (attributeName.equals("Exceptions")) {
                        if (checkedExceptions != NO_CHECKED_EXCEPTIONS) {
                            throw classFormatError("Duplicate Exceptions attribute");
                        }
                        checkedExceptions = readCheckedExceptionsAttribute();
                    } else if (attributeName.equals("Deprecated")) {
                        flags |= Actor.DEPRECATED;
                    } else if (attributeName.equals("Synthetic")) {
                        flags |= Actor.ACC_SYNTHETIC;
                    } else if (attributeName.equals(MaxineFlags.NAME)) {
                        flags |= classfileStream.readSigned4();
                    } else if (majorVersion >= JAVA_1_5_VERSION) {
                        if (attributeName.equals("Signature")) {
                            genericSignature = constantPool.utf8At(classfileStream.readUnsigned2(), "signature index");
                        } else if (attributeName.equals("RuntimeVisibleAnnotations")) {
                            assert runtimeVisibleAnnotationsBytes == NO_RUNTIME_VISIBLE_ANNOTATION_BYTES;
                            runtimeVisibleAnnotationsBytes = classfileStream.readByteArray(attributeSize);
                        } else if (attributeName.equals("RuntimeVisibleParameterAnnotations")) {
                            runtimeVisibleParameterAnnotationsBytes = classfileStream.readByteArray(attributeSize);
                        } else if (attributeName.equals("AnnotationDefault")) {
                            annotationDefaultBytes = classfileStream.readByteArray(attributeSize);
                        } else {
                            classfileStream.skip(attributeSize);
                        }
                    } else {
                        classfileStream.skip(attributeSize);
                    }

                    final int distance = classfileStream.getPosition() - startPosition;
                    if (attributeSize != distance) {
                        final String message = "Invalid attribute_length for " + attributeName + " attribute (reported " + attributeSize + " != parsed " + distance + ")";
                        throw classFormatError(message);
                    }
                }

                if (Actor.isAbstract(flags) || Actor.isNative(flags)) {
                    if (codeAttribute != null) {
                        throw classFormatError("Code attribute supplied for native or abstract method");
                    }
                } else {
                    if (codeAttribute == null) {
                        throw classFormatError("Missing Code attribute");
                    }
                }

                if (MaxineVM.isHosted()) {
                    // This must be called before clinit methods are filtered, since we especially want to find
                    // usages of the Unsafe class in static initializers that are not re-executed at run time.
                    UnsafeUsageChecker.methodLoadedHook(classDescriptor, name, codeAttribute);

                    if (isClinit) {
                        // Class initializer's for all boot image classes are run while bootstrapping and do not need to be in the boot image.
                        // The "max.loader.preserveClinitMethods" system property can be used to override this default behaviour.
                        // and specific (JDK) classes can be designated as overrides for reinitialisation purposes.
                        if (!MaxineVM.keepClassInit(classDescriptor)) {
                            continue nextMethod;
                        }
                    }

                    if (isStatic) {
                        // The following helps folding down enum switch code for known enums, which should not incur binary compatibility issues:
                        if (classDescriptor.string.startsWith("Lcom/sun/max") && name.toString().startsWith("$SWITCH_TABLE")) {
                            // TODO: check for the switch table name generated by javac
                            flags |= FOLD;
                        }
                    }
                }

                int substituteeIndex = -1;
                String intrinsic = null;
                Class accessor = null;

                boolean classHasNeverInlineAnnotation = false;
                if (MaxineVM.isHosted() && classLoader != HostedBootClassLoader.HOSTED_BOOT_CLASS_LOADER) {
                    for (Annotation annotation : getAnnotations(null, null)) {
                        if (annotation.annotationType() == NEVER_INLINE.class) {
                            classHasNeverInlineAnnotation = true;
                            break;
                        }
                    }
                }

                if (MaxineVM.isHosted() && classLoader != HostedBootClassLoader.HOSTED_BOOT_CLASS_LOADER
                        && runtimeVisibleAnnotationsBytes != NO_RUNTIME_VISIBLE_ANNOTATION_BYTES) {
                    for (Annotation annotation : getAnnotations(name, descriptor)) {
                        if (annotation.annotationType() == HOSTED_ONLY.class) {
                            continue nextMethod;
                        } else if (annotation.annotationType() == C_FUNCTION.class) {
                            ensureSignatureIsPrimitive(descriptor, C_FUNCTION.class);
                            ProgramError.check(Actor.isStatic(flags), "Cannot apply " + C_FUNCTION.class.getName() + " to a non-static method: " + memberString(name, descriptor));
                            ProgramError.check(Actor.isNative(flags), "Cannot apply " + C_FUNCTION.class.getName() + " to a non-native method: " + memberString(name, descriptor));
                            flags |= C_FUNCTION;
                        } else if (annotation.annotationType() == VM_ENTRY_POINT.class) {
                            ensureSignatureIsPrimitive(descriptor, VM_ENTRY_POINT.class);
                            ProgramError.check(Actor.isStatic(flags), "Cannot apply " + VM_ENTRY_POINT.class.getName() + " to a non-static method: " + memberString(name, descriptor));
                            flags |= VM_ENTRY_POINT;
                        } else if (annotation.annotationType() == NO_SAFEPOINT_POLLS.class) {
                            flags |= NO_SAFEPOINT_POLLS;
                        } else if (annotation.annotationType() == ACCESSOR.class) {
                            accessor = ((ACCESSOR) annotation).value();
                            flags |= UNSAFE;
                        } else if (annotation.annotationType() == PLATFORM.class) {
                            if (!Platform.platform().isAcceptedBy((PLATFORM) annotation)) {
                                continue nextMethod;
                            }
                        } else if (annotation.annotationType() == JDK_VERSION.class) {
                            if (!JDK.thisVersionOrNewer((JDK_VERSION) annotation)) {
                                continue nextMethod;
                            }
                        } else if (isBytecodeTemplate(annotation.annotationType())) {
                            flags |= TEMPLATE | UNSAFE | INLINE;
                        } else if (annotation.annotationType() == INLINE.class) {
                            flags |= INLINE;
                            ProgramError.check(!Actor.isAbstract(flags), "Cannot apply " + INLINE.class.getName() + " to an abstract method: " + memberString(name, descriptor));
                        } else if (annotation.annotationType() == NEVER_INLINE.class) {
                            flags |= NEVER_INLINE;
                        } else if (annotation.annotationType() == INTRINSIC.class) {
                            INTRINSIC intrinsicAnnotation = (INTRINSIC) annotation;
                            intrinsic = intrinsicAnnotation.value();
                            if (intrinsic == MaxineIntrinsicIDs.UNSAFE_CAST) {
                                String anno = INTRINSIC.class.getSimpleName() + "(UNSAFE_CAST)";
                                ProgramError.check(genericSignature == null, "Cannot apply " + anno + " to a generic method: " + memberString(name, descriptor));
                                ProgramError.check(descriptor.resultKind() != Kind.VOID, "Cannot apply " + anno + " to a void method: " + memberString(name, descriptor));
                                ProgramError.check(descriptor.numberOfParameters() == (isStatic ? 1 : 0), "Can only apply " + anno +
                                    " to a method with exactly one parameter: " + memberString(name, descriptor));
                            }
                        } else if (annotation.annotationType() == FOLD.class) {
                            flags |= FOLD;
                        } else if (annotation.annotationType() == LOCAL_SUBSTITUTION.class) {
                            // process any class-local substitutions
                            flags |= LOCAL_SUBSTITUTE;
                            final Utf8Constant substituteeName = SymbolTable.lookupSymbol(toSubstituteeName(name.toString()));
                            if (substituteeName != null) {
                                for (int j = nextMethodIndex - 1; j >= 0; --j) {
                                    final MethodActor substituteeActor = methodActors[j];
                                    if (substituteeActor.name.equals(substituteeName) && substituteeActor.descriptor().equals(descriptor)) {
                                        ProgramError.check(isStatic == substituteeActor.isStatic());
                                        Trace.line(2, "Substituted " + classDescriptor.toJavaString() + "." + substituteeName + descriptor);
                                        Trace.line(2, "       with " + classDescriptor.toJavaString() + "." + name + descriptor);
                                        substituteeIndex = j;
                                        name = substituteeName;

                                        // Copy the access level of the substitutee to the local substitute
                                        final int accessFlagsMask = ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED;
                                        flags &= ~accessFlagsMask;
                                        flags |= substituteeActor.flags() & accessFlagsMask;

                                        flags |= substituteeActor.flags() & SUBSTITUTION_ADOPTED_FLAGS;
                                        break;
                                    }
                                }
                            }
                            ProgramError.check(substituteeIndex != -1, "Could not find substitutee for local substitute method: " + memberString(name, descriptor));

                        }

                        if (intrinsic != null) {
                            // discard bytecode for intrinsic methods
                            codeAttribute = null;
                        }
                    }
                }

                if (runtimeVisibleAnnotationsBytes != NO_RUNTIME_VISIBLE_ANNOTATION_BYTES) {
                    if (MaxineVM.isHosted()) {
                        for (Annotation annotation : getAnnotations(name, descriptor)) {
                            if (annotation.annotationType() == CallerSensitive.class) {
                                flags |= CALLER_SENSITIVE;
                            }
                        }
                    } else {
                        flags |= parseRuntimeVisibleAnnotationsBytes(runtimeVisibleAnnotationsBytes);
                    }
                }

                if (classHasNeverInlineAnnotation && !Actor.isInline(flags)) {
                    flags |= NEVER_INLINE;
                }

                if (Actor.isNative(flags)) {
                    flags |= UNSAFE;
                }

                if (MaxineVM.isHosted() && classDescriptor.toKind() == Kind.WORD) {
                    // All non-static methods in Word types must be either @INLINE, @FOLD, @INTRINSIC, or @HOSTED_ONLY.
                    // They have special semantics, such as that no dynamic dispatch is possible, that no null checks must
                    // be performed when calling them, and that the receiver is not an Object.
                    // It simplifies the compiler if they never end up as method calls, but are always optimized away by the compiler.

                    if (Actor.isSynthetic(flags) && !Actor.isInitializer(flags) && !Actor.isDeclaredFoldable(flags) && !Actor.isInline(flags)) {
                        // When methods are overridden with a changed return type, javac automatically inserts a synthetic method with the
                        // return type of the super class method.  Since we cannot annotate such methods in source code, we add the inline flag here.
                        flags |= INLINE;
                    }

                    if (!Actor.isStatic(flags) && !Actor.isInline(flags) && !Actor.isDeclaredFoldable(flags) && intrinsic == null) {
                        throw FatalError.unexpected("Non-static methods in Word types must be either @INLINE, @FOLD, @INTRINSIC, or @HOSTED_ONLY: " + classDescriptor.toJavaString() + "." + name + " " + descriptor.toJavaString(true, true));
                    }
                }

                final MethodActor methodActor;
                // Make sure to check for static methods first since as of JDK 8 lambdas (which are static) and
                // static default methods may appear in interfaces.
                if (isStatic) {
                    methodActor = new StaticMethodActor(name, descriptor, flags, codeAttribute, intrinsic);
                } else if (isInterface) {
                    if (isClinit) {
                        methodActor = new StaticMethodActor(name, descriptor, flags, codeAttribute, intrinsic);
                    } else if (isInit) {
                        throw classFormatError("Interface cannot have a constructor");
                    } else {
                        methodActor = new InterfaceMethodActor(name, descriptor, flags, codeAttribute, intrinsic);
                    }
                } else {
                    methodActor = new VirtualMethodActor(name, descriptor, flags, codeAttribute, intrinsic);
                }

                classRegistry.set(GENERIC_SIGNATURE, methodActor, genericSignature);
                classRegistry.set(CHECKED_EXCEPTIONS, methodActor, checkedExceptions);
                classRegistry.set(Property.ACCESSOR, methodActor, accessor);
                classRegistry.set(RUNTIME_VISIBLE_ANNOTATION_BYTES, methodActor, runtimeVisibleAnnotationsBytes);
                classRegistry.set(RUNTIME_VISIBLE_PARAMETER_ANNOTATION_BYTES, methodActor, runtimeVisibleParameterAnnotationsBytes);
                classRegistry.set(ANNOTATION_DEFAULT_BYTES, methodActor, annotationDefaultBytes);

                if (MaxineVM.isHosted() && substituteeIndex != -1) {
                    methodActors[substituteeIndex] = methodActor;
                } else {
                    if (methodActorSet.add(methodActor) != null) {
                        throw classFormatError("Duplicate method name and signature: " + name + " " + descriptor);
                    }
                    if (isClinit) {
                        clinitIndex = nextMethodIndex;
                    }
                    methodActors[nextMethodIndex++] = methodActor;

                }
            } finally {
                exitContext();
            }
        }

        MethodActor[] result = nextMethodIndex < numberOfMethods ? Arrays.copyOf(methodActors, nextMethodIndex) : methodActors;
        if (clinitIndex != -1) {
            if (clinitIndex < result.length - 1) {
                // Move <clinit> to the end of the array. This makes member indexes
                // consistent, no matter whether the execution context omits clinit methods.
                MethodActor clinit = result[clinitIndex];
                for (int i = clinitIndex; i < result.length - 1; i++) {
                    result[i] = result[i + 1];
                }
                result[result.length - 1] = clinit;
            }
        }
        return result;
    }

    private int parseRuntimeVisibleAnnotationsBytes(byte[] runtimeVisibleAnnotationsBytes) {
        int flags = 0;
        ByteBuffer buf = ByteBuffer.wrap(runtimeVisibleAnnotationsBytes);
        short numAnnotations = buf.getShort();
        for (short j = 0; j < numAnnotations; j++) {
            flags |= parseAnnotation(buf);
        }
        return flags;
    }

    private int parseAnnotation(ByteBuffer buf) {
        short typeIndex = buf.getShort();
        if (constantPool.utf8At(typeIndex).equals("Lsun/reflect/CallerSensitive;")) {
            return CALLER_SENSITIVE;
        }
        short numElementValuePairs = buf.getShort();
        for (short k = 0; k < numElementValuePairs; k++) {
            parseElementValuePair(buf);
        }
        return 0;
    }

    private void parseElementValuePair(ByteBuffer buf) {
        short elementNameIndex = buf.getShort();
        assert constantPool.at(elementNameIndex) instanceof Utf8Constant;
        parseElementValue(buf);
    }

    private void parseElementValue(ByteBuffer buf) {
        char tag = (char) buf.get();
        short constValue;
        switch (tag) {
            case 'B':
            case 'C':
            case 'I':
            case 'S':
            case 'Z':
                constValue = buf.getShort(); // const_value_index
                assert constantPool.at(constValue) instanceof IntegerConstant;
                break;
            case 'D':
                constValue = buf.getShort(); // const_value_index
                assert constantPool.at(constValue) instanceof DoubleConstant;
                break;
            case 'F':
                constValue = buf.getShort(); // const_value_index
                assert constantPool.at(constValue) instanceof FloatConstant;
                break;
            case 'J':
                constValue = buf.getShort(); // const_value_index
                assert constantPool.at(constValue) instanceof LongConstant;
                break;
            case 's':
                constValue = buf.getShort(); // const_value_index
                assert constantPool.at(constValue) instanceof Utf8Constant;
                break;
            case 'e':
                constValue = buf.getShort(); // type_name_index
                assert constantPool.at(constValue) instanceof Utf8Constant;
                constValue = buf.getShort(); // const_name_index
                assert constantPool.at(constValue) instanceof Utf8Constant;
                break;
            case 'c':
                constValue = buf.getShort(); // class_info_index
                assert constantPool.at(constValue) instanceof Utf8Constant;
                break;
            case '@':
                parseAnnotation(buf);
                break;
            case '[':
                constValue = buf.getShort(); // num_values
                for (int i = 0; i < constValue; i++) {
                    parseElementValue(buf);
                }
                break;
            default:
                throw FatalError.unexpected("Unexpected tag: " + (int) tag);
        }
    }

    protected void readInnerClassesAttribute() {
        if (classInnerClasses != null) {
            throw classFormatError("Duplicate InnerClass attribute");
        }

        final int nInnerClasses = classfileStream.readUnsigned2();
        final InnerClassInfo[] innerClassInfos = new InnerClassInfo[nInnerClasses];
        final TypeDescriptor[] innerClasses = new TypeDescriptor[innerClassInfos.length];
        int nextInnerClass = 0;

        for (int i = 0; i < nInnerClasses; ++i) {
            final InnerClassInfo innerClassInfo = new InnerClassInfo(classfileStream, constantPool);
            final int innerClassIndex = innerClassInfo.innerClassIndex();
            final int outerClassIndex = innerClassInfo.outerClassIndex();

            // The JVM specification allows a null inner class but don't ask me what it means!
            if (innerClassIndex == 0) {
                continue;
            }

            // If no outer class is specified, then this entry denotes a local or anonymous class
            // that will have an EnclosingMethod attribute instead. That is, these classes are
            // not *immediately* enclosed by another class
            if (outerClassIndex == 0) {
                continue;
            }

            // If this entry refers to the current class, then the current class must be an inner class:
            // it's enclosing class is recorded and it's flags are updated.
            final TypeDescriptor innerClassDescriptor = constantPool.classAt(innerClassIndex, "inner class descriptor").typeDescriptor();
            if (innerClassDescriptor.equals(classDescriptor)) {
                if (classOuterClass != null) {
                    throw classFormatError("duplicate outer class");
                }
                classFlags |= INNER_CLASS;
                classOuterClass = constantPool.classAt(outerClassIndex).typeDescriptor();
                classFlags |= innerClassInfo.flags();
            }

            final TypeDescriptor outerClassDescriptor = constantPool.classAt(outerClassIndex, "outer class descriptor").typeDescriptor();
            if (outerClassDescriptor.equals(classDescriptor)) {
                // The inner class is enclosed by the current class
                innerClasses[nextInnerClass++] = constantPool.classAt(innerClassIndex).typeDescriptor();
            } else {
                // The inner class is enclosed by some class other than current class: ignore it
            }

            for (int j = 0; j < i; ++j) {
                final InnerClassInfo otherInnerClassInfo = innerClassInfos[j];
                if (otherInnerClassInfo != null) {
                    if (innerClassIndex == otherInnerClassInfo.innerClassIndex() && outerClassIndex == otherInnerClassInfo.outerClassIndex()) {
                        throw classFormatError("Duplicate entry in InnerClasses attribute");
                    }
                }
            }
            innerClassInfos[i] = innerClassInfo;
        }

        if (nextInnerClass != 0) {
            if (nextInnerClass == innerClasses.length) {
                classInnerClasses = innerClasses;
            } else {
                classInnerClasses = new TypeDescriptor[nextInnerClass];
                System.arraycopy(innerClasses, 0, classInnerClasses, 0, nextInnerClass);
            }
        }
    }

    protected EnclosingMethodInfo readEnclosingMethodAttribute() {
        final int classIndex = classfileStream.readUnsigned2();
        final int nameAndTypeIndex = classfileStream.readUnsigned2();

        final ClassConstant holder = constantPool.classAt(classIndex);
        final String name;
        final String descriptor;
        if (nameAndTypeIndex != 0) {
            final NameAndTypeConstant nameAndType = constantPool.nameAndTypeAt(nameAndTypeIndex);
            name = nameAndType.name().toString();
            descriptor = nameAndType.descriptorString();
        } else {
            name = null;
            descriptor = null;
        }

        return new EnclosingMethodInfo(holder.typeDescriptor(), name, descriptor);
    }

    protected ClassActor resolveSuperClass(int superClassIndex, boolean isInterface) {
        final ClassActor superClassActor;
        if (superClassIndex != 0) {
            final TypeDescriptor superClassDescriptor = constantPool.classAt(superClassIndex, "super class descriptor").typeDescriptor();

            if (superClassDescriptor.equals(classDescriptor)) {
                throw classFormatError("Class cannot be its own super class");
            }

            /*
             * If this is an interface class, its superclass must be
             * java.lang.Object.
             */
            if (isInterface && !superClassDescriptor.equals(OBJECT)) {
                throw classFormatError("Interface class must inherit from java.lang.Object");
            }

            /*
             * Now ensure the super class is resolved
             */
            superClassActor = constantPool.classAt(superClassIndex).resolve(constantPool, superClassIndex);

            /*
             * Cannot inherit from an array class.
             */
            if (superClassActor.isArrayClass()) {
                throw classFormatError("Cannot inherit from array class");
            }

            /*
             * The superclass cannot be an interface. From the
             * JVM Spec section 5.3.5:
             *
             *   If the class of interface named as the direct
             *   superclass of C is in fact an interface, loading
             *   throws an IncompatibleClassChangeError.
             */
            if (superClassActor.isInterface()) {
                throw classFormatError("Cannot extend an interface class");
            }

            /*
             * The superclass cannot be final.
             */
            if (superClassActor.isFinal()) {
                throw verifyError("Cannot extend a final class " + superClassActor.name);
            }
        } else {
            if (!classDescriptor.equals(OBJECT)) {
                throw classFormatError("missing required super class");
            }
            superClassActor = null;
        }
        return superClassActor;
    }

    /**
     * Loads a class from the configured {@linkplain #classfileStream class file stream}.
     *
     * @param nameExpected the expected name of the class in the stream (can be {@code null})
     * @param isRemote specifies if the stream is from a remote/untrusted (e.g. network) source. This is mainly used to
     *            determine the default bytecode verification policy for the class.
     */
    private ClassActor loadClass0(String nameExpected, boolean isRemote) {
        readMagic();
        final char minorVersionChar = (char) classfileStream.readUnsigned2();
        final char majorVersionChar = (char) classfileStream.readUnsigned2();

        verifyVersion(majorVersionChar, minorVersionChar);
        constantPool = new ConstantPool(classLoader, classfileStream, majorVersionChar);
        majorVersion = majorVersionChar;

        classFlags = classfileStream.readUnsigned2();

        verifyClassFlags(classFlags, majorVersionChar);
        final boolean isInterface = Actor.isInterface(classFlags);

        final int thisClassIndex = classfileStream.readUnsigned2();
        classDescriptor = constantPool.classAt(thisClassIndex, "this class descriptor").typeDescriptor();

        String nameLoaded = classDescriptor.toJavaString();

        if (nameExpected != null && !nameExpected.replace('/', '.').equals(nameLoaded)) {
            /*
             * VMSpec 5.3.5:
             *
             *   Otherwise, if the purported representation does not actually
             *   represent a class named N, loading throws an instance of
             *   NoClassDefFoundError or an instance of one of its
             *   subclasses.
             */
            throw noClassDefFoundError("'this_class' indicates wrong type expected=" + nameExpected + ", loaded=" + nameLoaded);
        }
        Utf8Constant name = SymbolTable.makeSymbol(nameLoaded);

        final int superClassIndex = classfileStream.readUnsigned2();
        final ClassActor superClassActor = resolveSuperClass(superClassIndex, isInterface);

        final InterfaceActor[] interfaceActors = readInterfaces();
        final FieldActor[] fieldActors = readFields(isInterface);
        final MethodActor[] methodActors = readMethods(isInterface);

        String sourceFileName = NO_SOURCE_FILE_NAME;
        byte[] runtimeVisibleAnnotationsBytes = NO_RUNTIME_VISIBLE_ANNOTATION_BYTES;
        BootstrapMethod[] bootstrapMethods = NO_BOOTSTRAP_METHODS;
        Utf8Constant genericSignature = NO_GENERIC_SIGNATURE;
        EnclosingMethodInfo enclosingMethodInfo = NO_ENCLOSING_METHOD_INFO;

        int nAttributes = classfileStream.readUnsigned2();
        while (nAttributes-- != 0) {
            final int attributeNameIndex = classfileStream.readUnsigned2();
            final String attributeName = constantPool.utf8At(attributeNameIndex, "attribute name").toString();
            final int attributeSize = classfileStream.readSize4();
            final int startPosition = classfileStream.getPosition();
            if (attributeName.equals("SourceFile")) {
                if (sourceFileName != NO_SOURCE_FILE_NAME) {
                    throw classFormatError("Duplicate SourceFile attribute");
                }
                final int sourceFileNameIndex = classfileStream.readUnsigned2();
                sourceFileName = constantPool.utf8At(sourceFileNameIndex, "source file name").toString();
            } else if (attributeName.equals("Deprecated")) {
                classFlags |= Actor.DEPRECATED;
            } else if (attributeName.equals("Synthetic")) {
                classFlags |= Actor.ACC_SYNTHETIC;
            } else if (attributeName.equals("InnerClasses")) {
                readInnerClassesAttribute();
            } else if (attributeName.equals(MaxineFlags.NAME)) {
                classFlags |= classfileStream.readSigned4();
            } else if (majorVersion >= JAVA_1_5_VERSION) {
                if (attributeName.equals("Signature")) {
                    genericSignature = constantPool.utf8At(classfileStream.readUnsigned2(), "signature index");
                } else if (attributeName.equals("RuntimeVisibleAnnotations")) {
                    runtimeVisibleAnnotationsBytes = classfileStream.readByteArray(attributeSize);
                } else if (majorVersion >= JAVA_7_VERSION && attributeName.equals("BootstrapMethods")) {
                    if (bootstrapMethods != NO_BOOTSTRAP_METHODS) {
                        throw classFormatError("Duplicate BootstrapMethods attribute");
                    }
                    bootstrapMethods = readBootstrapMethods(classfileStream);
                } else if (attributeName.equals("EnclosingMethod")) {
                    if (enclosingMethodInfo != null) {
                        throw classFormatError("Duplicate EnclosingMethod attribute");
                    }
                    enclosingMethodInfo = readEnclosingMethodAttribute();
                } else {
                    classfileStream.skip(attributeSize);
                }
            } else {
                classfileStream.skip(attributeSize);
            }

            if (attributeSize != classfileStream.getPosition() - startPosition) {
                throw classFormatError("Invalid attribute length for " + name + " attribute");
            }
        }

        // inherit the FINALIZER flag from the super class actor
        if (superClassActor != null) {
            if (superClassActor.hasFinalizer()) {
                classFlags |= Actor.FINALIZER;
            }
        } else {
            // clear the finalizer bit for the java.lang.Object class; otherwise all classes would have it!
            classFlags &= ~Actor.FINALIZER;
        }

        if (isRemote) {
            classFlags |= Actor.REMOTE;
        }

        // Ensure there are no trailing bytes
        classfileStream.checkEndOfFile();

        if (MaxineVM.isHosted() && runtimeVisibleAnnotationsBytes != null) {
            for (Annotation annotation : getAnnotations(null, null)) {
                if (annotation.annotationType() == HOSTED_ONLY.class) {
                    throw new HostOnlyClassError(name.string);
                }
            }
        }

        final ClassActor classActor;
        if (isInterface) {
            classActor = createInterfaceActor(
                            constantPool,
                            classLoader,
                            name,
                            majorVersionChar,
                            minorVersionChar,
                            classFlags,
                            interfaceActors,
                            fieldActors,
                            methodActors,
                            genericSignature,
                            runtimeVisibleAnnotationsBytes,
                            bootstrapMethods,
                            sourceFileName,
                            classInnerClasses,
                            classOuterClass,
                            enclosingMethodInfo);
        } else {
            classActor = createTupleOrHybridClassActor(
                            constantPool,
                            classLoader,
                            name,
                            majorVersionChar,
                            minorVersionChar,
                            classFlags,
                            superClassActor,
                            interfaceActors,
                            fieldActors,
                            methodActors,
                            genericSignature,
                            runtimeVisibleAnnotationsBytes,
                            bootstrapMethods,
                            sourceFileName,
                            classInnerClasses,
                            classOuterClass,
                            enclosingMethodInfo);
        }
        if (superClassActor != null) {
            superClassActor.checkAccessBy(classActor);
        }

        return classActor;
    }

    private BootstrapMethod[] readBootstrapMethods(ClassfileStream classfileStream) {
        final int numBootstrapMethods = classfileStream.readUnsigned2();
        BootstrapMethod[] bootstrapMethods = new BootstrapMethod[numBootstrapMethods];

        for (int i = 0; i < numBootstrapMethods; i++) {
            bootstrapMethods[i] = readBootstrapMethod(classfileStream);
        }

        return bootstrapMethods;
    }

    private BootstrapMethod readBootstrapMethod(ClassfileStream classfileStream) {
        final int   bootstrapMethodRef    = classfileStream.readUnsigned2();
        final int   numBootstrapArguments = classfileStream.readUnsigned2();
        final int[] bootstrapArgumentRefs = new int[numBootstrapArguments];

        for (int i = 0; i < numBootstrapArguments; i++) {
            bootstrapArgumentRefs[i] = classfileStream.readUnsigned2();
        }

        return new BootstrapMethod.Unresolved(bootstrapMethodRef, bootstrapArgumentRefs);
    }

    public static VMStringOption saveClassDir = VMOptions.register(new VMStringOption("-XX:SaveClassDir=", false, null,
        "Directory to which the classfiles of loaded classes should be written."), MaxineVM.Phase.STARTING);

    /**
     * Loads a class from the configured {@linkplain #classfileStream class file stream}.
     *
     * @param name the expected name of the class in the stream (can be {@code null})
     * @param source
     * @param isRemote specifies if the stream is from a remote/untrusted (e.g. network) source. This is mainly used to
     *            determine the default bytecode verification policy for the class.
     */
    private ClassActor loadClass(final String name, Object source, boolean isRemote) {
        try {
            String optSource = null;
            boolean verbose = verboseOption.verboseClass || Trace.hasLevel(2);
            if (verbose) {
                if (source != null) {
                    Log.println("[Loading " + name + " from " + source + "]");
                } else {
                    optSource = classLoader == null ? "generated data" : classLoader.getClass().getName();
                    Log.println("[Loading " + name + " from " + optSource + "]");
                }
            }
            enterContext(new Object() {
                @Override
                public String toString() {
                    return "loading " + name;
                }
            });
            final ClassActor classActor = loadClass0(name, isRemote);

            if (verboseOption.verboseClass || Trace.hasLevel(2)) {
                if (source != null) {
                    Log.println("[Loaded " + name + " from " + source + "]");
                } else {
                    Log.println("[Loaded " + name + " from " + optSource + "]");
                }
            }
            return classActor;
        } finally {
            exitContext();
        }
    }

    /**
     * Records the state of the class transformation process (when agents are active).
     * Only one thread, {@code definingThread}, handles the transform, other concurrent
     * requests wait for {@code definingThread} to complete.
     */
    private static class ClassTransformState {
        static final int INFLIGHT = 1;
        static final int DEFINED = 2;
        int state;
        final Thread definingThread;
        AgentTransformResult transformResult;

        ClassTransformState() {
            state = INFLIGHT;
            definingThread = Thread.currentThread();
        }
    }

    /**
     * Uniquely identifies an attempted definition for a given class in a given classloader.
     */
    private static class ClClass {
        final ClassLoader classLoader;
        final String className;

        ClClass(ClassLoader classLoader, String className) {
            this.classLoader = classLoader;
            this.className = className;
        }

        @Override
        public boolean equals(Object other) {
            ClClass otherClClass = (ClClass) other;
            return classLoader == otherClClass.classLoader && className.equals(otherClClass.className);
        }

        @Override
        public int hashCode() {
            return classLoader.hashCode() ^ className.hashCode();
        }
    }

    /**
     * Records the class transformation state (when agents are active).
     */
    private static ConcurrentMap<ClClass, ClassTransformState> classTransformStateMap = new ConcurrentHashMap<ClClass, ClassTransformState>();

    /**
     * Captures changes to the incoming parameters to {@link ClassfileReader#defineClassActor}if a transform occurred.
     */
    private static class AgentTransformResult {
        byte[] bytes;
        int length;
        int offset;

        AgentTransformResult(byte[] bytes, int length, int offset) {
            this.bytes = bytes;
            this.offset = offset;
            this.length = length;
        }
    }

    /**
     * Check for bytecode transformation by agents prior to class definition.
     * @return modified bytecode array or {@code null} if no change
     */
    private static AgentTransformResult checkAgentTransform(String name, ClassLoader classLoader, byte[] bytes, ProtectionDomain protectionDomain,
                    int offset, int length) {
        final Instrumentation instrumentation = InstrumentationManager.getInstrumentation();
        boolean vmtiAgents = !MaxineVM.isHosted() && VMTI.handler().classFileLoadHookHandled();

        /*
         * When we have (Java) agents active that might transform the bytecode there are additional concurrency considerations.
         * First, it isn't ok to call the agents multiple times to transform the same class bytes. Second, we have
         * to worry about recursion from an agent that happens to use (and therefore tries to recursively define)
         * the class we are defining here.
         *
         * N.B. Although the specification does not discuss this, Hotspot invokes the agent methods whenever the class
         * is loaded in a distinct classloader. So we use ClClass as the key in the state map.
         *
         */

        if (instrumentation != null || vmtiAgents) {

            ClassTransformState proto = new ClassTransformState();
            ClassTransformState classTransformState = classTransformStateMap.putIfAbsent(new ClClass(classLoader, name), proto);

            if (classTransformState == null) {
                classTransformState = proto;
                // no conflict, this thread will now handle this class

                boolean changed = false;
                // zero base and truncate the input array if necessary
                if (offset != 0 || length != bytes.length) {
                    byte[] zeroBase = new byte[length];
                    System.arraycopy(bytes, offset, zeroBase, 0, length);
                    bytes = zeroBase;
                }
                String iName = name == null ? null : name.replace('.', '/');
                ClassLoader agentClassLoader = classLoader == BootClassLoader.BOOT_CLASS_LOADER ? null : classLoader;
                if (vmtiAgents) {
                    // Call JVMTI agents first and then pass to the javaagent agents
                    final byte[] tBytes = VMTI.handler().classFileLoadHook(agentClassLoader, iName, protectionDomain, bytes);
                    if (tBytes != null) {
                        changed = true;
                        bytes = tBytes;
                    }

                }
                if (instrumentation != null) {
                    final byte[] tBytes = InstrumentationManager.transform(agentClassLoader, iName, null, protectionDomain, bytes, false);
                    if (tBytes != null) {
                        changed = true;
                        bytes = tBytes;
                    }
                }

                synchronized (classTransformState) {
                    classTransformState.state = ClassTransformState.DEFINED;
                    if (changed) {
                        classTransformState.transformResult = new AgentTransformResult(bytes, 0, bytes.length);
                    }
                    // notify all the threads that are waiting for us to finish
                    classTransformState.notifyAll();
                }
            } else {
                // another thread is handling the definition or we have recursion
                synchronized (classTransformState) {
                    if (classTransformState.state == ClassTransformState.INFLIGHT) {
                        if (classTransformState.definingThread == Thread.currentThread()) {
                            // recursion from the agent itself, we just need to let this continue as a normal define
                            // as we have already prevented recursive transformation
                            return null;
                        } else {
                            // parallel define, let the other thread handle it
                        }
                        while (classTransformState.state == ClassTransformState.INFLIGHT) {
                            try {
                                classTransformState.wait();
                            } catch (InterruptedException ex) {

                            }
                        }
                    } else {
                        // another thread already handled it, so no transform or wait needed
                    }
                }
            }
            // which ever thread handled it, this is the result
            return classTransformState.transformResult;
        } else {
            return null; // no agents, so no change
        }
    }

    /**
     * Converts an array of bytes into a {@code ClassActor}.
     *
     * @param name the name of the class being defined
     * @param classLoader the defining class loader
     * @param bytes The bytes that make up the class data. The bytes should have the format of a valid class file as
     *            defined by the <a href="http://java.sun.com/docs/books/vmspec/">Java Virtual Machine Specification</a>.
     * @param protectionDomain the ProtectionDomain of the class. This value can be null.
     * @param source a object whose {@code toString()} method describes source location of the bytes. This is purely
     *            informative detail. For example, it may be used to provide the output for verbose class loading. This
     *            value can be null.
     * @param isRemote specifies if the stream is from a remote/untrusted (e.g. network) source. This is mainly used to
     *            determine the default bytecode verification policy for the class.
     * @return the {@code ClassActor} object created from the data, and optional {@code ProtectionDomain}
     * @throws ClassFormatError if the data did not contain a valid class
     * @throws NoClassDefFoundError if {@code name} is not equal to the {@linkplain Class#getName() binary name} of the
     *             class specified by {@code bytes}
     */
    public static ClassActor defineClassActor(String name, ClassLoader classLoader, byte[] bytes, ProtectionDomain protectionDomain, Object source, boolean isRemote) {
        return defineClassActor(name, classLoader, bytes, 0, bytes.length, protectionDomain, source, isRemote);
    }

    /**
     * Converts an array of bytes into a {@code ClassActor}.
     *
     * @param name the name of the class being defined (can be {@code null})
     * @param classLoader the defining class loader
     * @param bytes the bytes that make up the class data. The bytes in positions {@code offset} through
     *            {@code offset + length - 1} should have the format of a valid class file as defined by the <a
     *            href="http://java.sun.com/docs/books/vmspec/">Java Virtual Machine Specification</a>.
     * @param offset the start offset in {@code bytes} of the class data
     * @param length the length of the class data
     * @param protectionDomain the ProtectionDomain of the class. This value can be null.
     * @param source a object whose {@code toString()} method describes source location of the bytes. This is purely
     *            informative detail. For example, it may be used to provide the output for verbose class loading. This
     *            value can be null.
     * @param isRemote specifies if the stream is from a remote/untrusted (e.g. network) source. This is mainly used to
     *            determine the default bytecode verification policy for the class.
     * @return the {@code ClassActor} object created from the data, and optional {@code ProtectionDomain}
     * @throws ClassFormatError if the data did not contain a valid class
     * @throws NoClassDefFoundError if {@code name} is not equal to the {@linkplain Class#getName() binary name} of the
     *             class specified by {@code bytes}
     */
    public static ClassActor defineClassActor(String name, ClassLoader classLoader, byte[] bytes, int offset, int length, ProtectionDomain protectionDomain, Object source, boolean isRemote) {
        AgentTransformResult transformResult = checkAgentTransform(name, classLoader, bytes, protectionDomain, offset, length);
        if (transformResult != null) {
            bytes = transformResult.bytes;
            offset = transformResult.offset;
            length = transformResult.length;
        }

        /*
         * This code can execute concurrently if the class is being defined by multiple threads.
         * Some redundant work is done here but ultimately only one thread wins and we get a single
         * ClassActor created.
         *
         * It is very important to return the value generated by ClassRegistry.define, which is where
         * the resolution of which thread wins the race is handled.
         */

        saveClassfile(name, bytes);
        final ClassfileStream classfileStream = new ClassfileStream(bytes, offset, length);
        final ClassfileReader classfileReader = new ClassfileReader(classfileStream, classLoader);
        ClassActor classActor = classfileReader.loadClass(name, source, isRemote);
        classActor.setProtectionDomain(protectionDomain);

        final ClassActor definedClassActor = ClassRegistry.define(classActor);

        if (!MaxineVM.isHosted()) {
            // Maxine is unable to usefully distinguish CLASS_LOAD and CLASS_PREPARE events which, for example, JVMTI distinguishes,
            // as we need a ClassActor in order to create a Class object, so we just have the one event.
            VMTI.handler().classLoad(definedClassActor);
        }
        return definedClassActor;
    }

    /**
     * This exists (solely) for the purpose of being able to reify generated classes while hosted. These are needed so
     * that the actors for generated stubs can be created.
     */
    @HOSTED_ONLY
    private static final Map<String, byte[]> savedClassfiles = new TreeMap<String, byte[]>();

    @HOSTED_ONLY
    public static ClasspathFile findGeneratedClassfile(String name) {
        final byte[] classfileBytes = savedClassfiles.get(name);
        if (classfileBytes != null) {
            return new ClasspathFile(classfileBytes, null);
        }
        return null;
    }

    /**
     * Writes all the class files that have been {@linkplain #saveClassfile(String, byte[]) saved} (either explicitly
     * or as a side effect of being loaded to a given jar file).
     *
     * @param jarFile where the class files are to be written
     */
    @HOSTED_ONLY
    public static void writeClassfilesToJar(File jarFile) {
        try {
            final JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(jarFile));
            jarOutputStream.setLevel(Deflater.BEST_COMPRESSION);
            long timestamp = System.currentTimeMillis();
            for (Map.Entry<String, byte[]> entry : savedClassfiles.entrySet()) {
                String name = entry.getKey();
                String classfilePath = name.replace('.', '/') + ".class";
                final JarEntry jarEntry = new JarEntry(classfilePath);
                jarEntry.setTime(timestamp);
                try {
                    jarOutputStream.putNextEntry(jarEntry);
                    jarOutputStream.write(entry.getValue());
                    jarOutputStream.closeEntry();
                } catch (IOException e) {
                    throw ProgramError.unexpected("IO error saving class file for " + name, e);
                }
            }
            jarOutputStream.close();
        } catch (IOException e) {
            throw ProgramError.unexpected("IO error writing saved classes to " + jarFile, e);
        }
    }

    /**
     * Writes all the class files that have been {@linkplain #saveClassfile(String, byte[]) saved} (either explicitly
     * or as a side effect of being loaded to a given directory).
     *
     * @param directory where the class files are to be written
     */
    @HOSTED_ONLY
    public static void writeClassfilesToDir(File directory) {
        for (Map.Entry<String, byte[]> entry : savedClassfiles.entrySet()) {
            String name = entry.getKey();
            String classfilePath = name.replace('.', File.separatorChar) + ".class";
            File classfile = new File(directory, classfilePath);
            try {
                FileOutputStream out = new FileOutputStream(classfile);
                out.write(entry.getValue());
                out.close();
            } catch (IOException e) {
                throw ProgramError.unexpected("IO error saving class file to " + classfile, e);
            }
        }
    }

    /**
     * Saves a copy of a class file in the directory specified by the value of the {@link #saveClassDir} option.
     * This method does nothing if the value of the {@code saveClassDir} option is {@code null}.
     *
     * @param name the (purported) name of the class represented in {@code classfileBytes}
     * @param classfileBytes the class file bytes to save
     */
    public static void saveClassfile(String name, byte[] classfileBytes) {
        if (name == null) {
            return;
        }
        String classfilePath = Classes.getPackageName(name).replace('.', File.separatorChar) + File.separatorChar + Classes.getSimpleName(name) + ".class";
        if (MaxineVM.isHosted()) {
            synchronized (savedClassfiles) {
                byte[] existingClassfile = savedClassfiles.put(name, classfileBytes);
                if (existingClassfile != null && !Arrays.equals(existingClassfile, classfileBytes)) {
                    try {
                        Class<?> javaClass = Class.forName(name);
                        if (javaClass.getAnnotation(HOSTED_ONLY.class) != null) {
                            // Don't emit messages for host only classes as these class files are only generated
                            // as an unavoidable side effect of bytecode intrinsification (see Intrinsics)
                        } else {
                            ProgramWarning.message("class with same name but different class file bytes generated twice: " + name);
                        }
                    } catch (ClassNotFoundException e) {
                    }
                }
            }
        }

        if (saveClassDir.getValue() != null) {
            File classfile = new File(saveClassDir.getValue(), classfilePath);
            try {
                classfile.getParentFile().mkdirs();
                FileOutputStream out = new FileOutputStream(classfile);
                out.write(classfileBytes);
                out.close();
                if (verboseOption.verboseClass) {
                    Log.println("[Wrote class file to " + classfile + "]");
                }
            } catch (IOException e) {
                Log.println("[Error writing class file bytes to " + classfile + ": " + e + "]");
            }
        }

    }
}
