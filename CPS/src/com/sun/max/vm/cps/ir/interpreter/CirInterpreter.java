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
package com.sun.max.vm.cps.ir.interpreter;

import java.lang.reflect.*;

import com.sun.max.program.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.graft.*;
import com.sun.max.vm.compiler.builtin.*;
import com.sun.max.vm.cps.cir.*;
import com.sun.max.vm.cps.cir.builtin.*;
import com.sun.max.vm.cps.cir.optimize.*;
import com.sun.max.vm.cps.cir.transform.*;
import com.sun.max.vm.cps.cir.variable.*;
import com.sun.max.vm.cps.ir.*;
import com.sun.max.vm.jni.*;
import com.sun.max.vm.value.*;

/**
 * Interpreter for CIR constructs.
 *
 * Used for unit testing without compiling beyond CIR.
 *
 * @author Bernd Mathiske
 */
public class CirInterpreter extends IrInterpreter<CirMethod> {

    private final CirGenerator cirGenerator;
    private CirOptimizer cirOptimizer;

    public CirInterpreter(CirGenerator cirGenerator) {
        this.cirGenerator = cirGenerator;
    }

    protected CirCall foldBuiltin(CirBuiltin cirBuiltin, CirValue[] cirArguments) {
        final Builtin builtin = cirBuiltin.builtin;
        if (builtin instanceof PointerBuiltin) {
            final Value[] arguments = CirFoldable.Static.cirArgumentsToValues(cirArguments, null);
            final CirValue normalContinuation = cirArguments[cirArguments.length - 2];
            try {
                if (builtin instanceof PointerLoadBuiltin) {
                    final PointerLoadBuiltin pointerLoadBuiltin = (PointerLoadBuiltin) builtin;
                    return new CirCall(normalContinuation, new CirConstant(pointerLoad(pointerLoadBuiltin.resultKind, arguments)));
                }
                final PointerStoreBuiltin pointerStoreBuiltin = (PointerStoreBuiltin) builtin;
                pointerStore(pointerStoreBuiltin.kind(), arguments);
                return new CirCall(normalContinuation, CirCall.NO_ARGUMENTS);
            } catch (Throwable throwable) {
                //TODO: catch only those exceptions that are caused by the interpreted CIR code
                final CirValue exceptionContinuation = cirArguments[cirArguments.length - 1];
                return new CirCall(exceptionContinuation, CirConstant.fromObject(throwable));
            }
        }
        try {
            return cirBuiltin.fold(cirOptimizer, cirArguments);
        } catch (CirFoldingException cirFoldingException) {
            return CirFoldable.Static.createExceptionCall(cirFoldingException.getCause(), cirArguments);
        }
    }

    private final CirVariableFactory variableFactory = new CirVariableFactory();

    private CirValue evaluate(CirCall cirCall) throws InvocationTargetException {
        CirCall call = cirCall;
        while (true) {
            Trace.line(3);
            Trace.line(3, "----------------- CIR interpretation:  -----------------");
            call.trace(3);
            CirValue procedure = call.procedure();
            CirValue[] arguments = call.arguments();
            if (procedure instanceof CirConstant) {
                final MethodID methodID = MethodID.fromWord(procedure.value().asWord());
                final ClassMethodActor classMethodActor = (ClassMethodActor) MethodID.toMethodActor(methodID);
                procedure = cirGenerator.createIrMethod(classMethodActor);
            }
            if (procedure instanceof CirMethod) {
                final CirMethod method = (CirMethod) procedure;
                try {
                    call = method.fold(cirOptimizer, arguments);
                } catch (CirFoldingException cirFoldingException) {
                    ExceptionDispatcher.INTERPRETER_EXCEPTION.set(cirFoldingException.getCause());
                    call = CirFoldable.Static.createExceptionCall(cirFoldingException.getCause(), arguments);
                }
            } else if (procedure instanceof CirBuiltin) {
                call = foldBuiltin((CirBuiltin) procedure, arguments);
            } else if (procedure instanceof CirBlock) {
                final CirBlock block = (CirBlock) procedure;
                call.setProcedure(block.closure());
            } else if (procedure instanceof CirVariable) {
                if (procedure instanceof CirNormalContinuationParameter) {
                    if (arguments.length == 0) {
                        return new CirConstant(VoidValue.VOID);
                    }
                    return arguments[0];
                }
                if (procedure instanceof CirExceptionContinuationParameter) {
                    assert arguments.length == 1;
                    final CirConstant throwable = (CirConstant) arguments[0];
                    ExceptionDispatcher.INTERPRETER_EXCEPTION.set(null);
                    throw new InvocationTargetException((Throwable) throwable.value().asObject());
                }
                ProgramError.unexpected("call to variable other than continuation parameter: " + procedure);
            } else if (procedure instanceof CirSwitch) {
                final CirSwitch method = (CirSwitch) procedure;
                try {
                    call = method.fold(cirOptimizer, arguments);
                } catch (CirFoldingException cirFoldingException) {
                    // Folding a CirSwitch should never fail
                    throw ProgramError.unexpected(cirFoldingException);
                }
            } else {
                assert procedure instanceof CirClosure;
                final CirClosure closure = CirReplication.apply((CirClosure) procedure);
                final CirVariable[] parameters = closure.parameters();
                assert arguments.length == parameters.length;
                call = CirBetaReduction.applyMultiple(closure, arguments);
            }
        }
    }

    private CirValue[] valuesToCirArguments(Value[] arguments) {
        final CirValue[] cirArguments = new CirValue[arguments.length + 2];
        for (int i = 0; i < arguments.length; i++) {
            cirArguments[i] = new CirConstant(arguments[i]);
        }
        cirArguments[cirArguments.length - 2] = variableFactory.normalContinuationParameter();
        cirArguments[cirArguments.length - 1] = variableFactory.exceptionContinuationParameter();
        return cirArguments;
    }

    @Override
    public Value execute(IrMethod method, Value... arguments) throws InvocationTargetException {
        CirMethod cirMethod = (CirMethod) method;
        CirCall call = new CirCall(cirMethod, arguments.length > 0 ? valuesToCirArguments(arguments) : CirCall.NO_ARGUMENTS);
        cirOptimizer = new CirOptimizer(cirGenerator, cirMethod, call, CirInliningPolicy.NONE);

        if (cirMethod.isNative()) {
            // native stubs cannot be interpreted at the CIR level so the CIR interpreter simply
            // invokes the native method via reflection:
            call = cirMethod.fold(cirOptimizer, valuesToCirArguments(arguments));
        } else {
            call = new CirCall(cirMethod.copyClosure(), valuesToCirArguments(arguments));
        }
        final CirValue result = evaluate(call);
        if (result instanceof CirConstant) {
            final CirConstant cirConstant = (CirConstant) result;
            return cirMethod.resultKind().convert(cirConstant.value());
        }
        return ReferenceValue.from(result);
    }
}