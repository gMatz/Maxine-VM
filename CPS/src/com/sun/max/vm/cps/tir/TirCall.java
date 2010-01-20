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

package com.sun.max.vm.cps.tir;

import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.cps.tir.pipeline.*;
import com.sun.max.vm.type.*;

public abstract class TirCall extends TirInstruction {
    protected final ClassMethodActor method;
    protected final TirInstruction[] operands;

    public TirCall(ClassMethodActor method, TirInstruction... arguments) {
        this.method = method;
        this.operands = arguments;
    }

    @Override
    public void visitOperands(TirInstructionVisitor visitor) {
        for (TirInstruction argument : operands) {
            argument.accept(visitor);
        }
    }

    public TirInstruction[] operands() {
        return operands;
    }

    public ClassMethodActor method() {
        return method;
    }

    public Kind resultKind() {
        return method.resultKind();
    }

    @Override
    public Kind kind() {
        return resultKind().toStackKind();
    }
}