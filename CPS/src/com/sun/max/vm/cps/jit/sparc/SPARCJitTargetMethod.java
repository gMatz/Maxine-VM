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
package com.sun.max.vm.cps.jit.sparc;

import com.sun.max.unsafe.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.cps.jit.*;
import com.sun.max.vm.cps.target.sparc.*;
import com.sun.max.vm.runtime.sparc.*;

/**
 * Target method generated by the SPARC JIT compiler.
 *
 * @author Laurent Daynes
 * @author Paul Caprioli
 */
public class SPARCJitTargetMethod extends JitTargetMethod implements SPARCTargetMethod {

    public SPARCJitTargetMethod(ClassMethodActor classMethodActor, RuntimeCompilerScheme compilerScheme) {
        super(classMethodActor, compilerScheme);
    }

    @Override
    public void forwardTo(TargetMethod newTargetMethod) {
        SPARCTargetMethod.Static.forwardTo(this, newTargetMethod);
    }

    @Override
    public int bytecodePositionForCallSite(Pointer returnInstructionPointer) {
        // The instruction pointer is the call machine instruction.
        return bytecodePositionFor(returnInstructionPointer);
    }

    @Override
    public void patchCallSite(int callOffset, Word callEntryPoint) {
        SPARCTargetMethod.Static.patchCallSite(this, callOffset, callEntryPoint);
    }

    @Override
    public int registerReferenceMapSize() {
        return SPARCTrapStateAccess.registerReferenceMapSize();
    }

}
