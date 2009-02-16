/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.max.vm.monitor.modal.modehandlers.inflated;

import com.sun.max.annotate.*;
import com.sun.max.unsafe.*;
import com.sun.max.unsafe.box.*;
import com.sun.max.vm.monitor.modal.modehandlers.*;
import com.sun.max.vm.monitor.modal.sync.*;

/**
 * Abstracts access to an inflated lock word's bit fields.
 *
 * @author Simon Wilkinson
 */
public abstract class InflatedMonitorLockWord64 extends HashableLockWord64 {

    /*
     * Field layout:
     *
     * bit [63............................... 1  0]     Shape         Binding   Lock-state
     *
     *     [            0           ][ hash ][0][1]     Inflated      Unbound   Unlocked
     *     [ Pointer to JavaMonitor object  ][1][1]     Inflated      Bound     Unlocked or locked
     *     [           Undefined            ][m][0]     Lightweight
     *
     */

    private static final Address MONITOR_MASK = Word.allOnes().asAddress().shiftedLeft(NUMBER_OF_MODE_BITS);

    protected InflatedMonitorLockWord64() {
    }

    /**
     * Boxing-safe cast of a <code>Word</code> to a <code>InflatedMonitorLockWord64</code>.
     *
     * @param word the word to cast
     * @return the cast word
     */
    @UNCHECKED_CAST
    public static final InflatedMonitorLockWord64 from(Word word) {
        return new BoxedInflatedMonitorLockWord64(word);
    }

    /**
     * Tests if the given lock word is an <code>InflatedMonitorLockWord64</code>.
     *
     * @param lockWord the lock word to test
     * @return true if <code>lockWord</code> is an <code>InflatedMonitorLockWord64</code>; false otherwise
     */
    @INLINE
    public static final boolean isInflatedMonitorLockWord(ModalLockWord64 lockWord) {
        return InflatedMonitorLockWord64.from(lockWord).isInflated();
    }

    /**
     * Tests if this <code>InflatedMonitorLockWord64</code> is bound to a <code>JavaMonitor</code>.
     *
     * @return true if bound, false otherwise
     */
    @INLINE
    public final boolean isBound() {
        return asAddress().isBitSet(MISC_BIT_INDEX);
    }

    /**
     * Returns a new <code>InflatedMonitorLockWord64</code> which is bound to the given
     * <code>JavaMonitor</code> object.
     *
     * Note: The binding is only created one-way, i.e. the lock word points to the inflated
     * monitor, but not the other way-around.
     *
     * @param monitor the monitor to which the <code>InflatedMonitorLockWord64</code> should be bound
     * @return a new <code>InflatedMonitorLockWord64</code> which is bound to <code>monitor</code>
     */
    @INLINE
    public static final InflatedMonitorLockWord64 boundFromMonitor(JavaMonitor monitor) {
        return from(UnsafeLoophole.objectToWord(monitor).asAddress().bitSet(SHAPE_BIT_INDEX).bitSet(MISC_BIT_INDEX));
    }

    /**
     * Gets the bound {@link JavaMonitor JavaMonitor} encoded into this lock word.
     *
     * @return this lock word's bound monitor
     */
    @INLINE
    public final JavaMonitor getBoundMonitor() {
        return (JavaMonitor) UnsafeLoophole.wordToObject(asAddress().and(MONITOR_MASK).asPointer());
    }

    /**
     * Returns a {@link Reference Reference} to the bound {@link JavaMonitor JavaMonitor} encoded into this lock word.
     *
     * @return this lock word's bound monitor
     */
    public final Word getBoundMonitorReferenceAsWord() {
        return asAddress().and(MONITOR_MASK).asPointer();
    }

    /**
     * (Image build support) Returns a new, unbound <code>InflatedMonitorLockWord64</code> with the given
     * hashcode installed into the hashcode field.
     *
     * @param hashcode the hashcode to install
     * @return the lock word
     */
    @INLINE
    public static final InflatedMonitorLockWord64 unboundFromHashcode(int hashcode) {
        return InflatedMonitorLockWord64.from(HashableLockWord64.from(Address.zero()).setHashcode(hashcode).asAddress().bitSet(SHAPE_BIT_INDEX));
    }
}
