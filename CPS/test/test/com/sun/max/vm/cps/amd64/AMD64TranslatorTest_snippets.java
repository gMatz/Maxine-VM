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
package test.com.sun.max.vm.cps.amd64;

import java.io.*;

import junit.framework.*;
import test.com.sun.max.vm.cps.*;

import com.sun.max.asm.*;
import com.sun.max.asm.dis.amd64.*;
import com.sun.max.io.*;
import com.sun.max.program.*;
import com.sun.max.vm.compiler.snippet.*;
import com.sun.max.vm.cps.target.*;

/**
 * Test whether the internal Snippets get translated at all.
 * This test is subsumed by each of the other translator tests.
 *
 * @author Bernd Mathiske
 */
@org.junit.runner.RunWith(org.junit.runners.AllTests.class)
public class AMD64TranslatorTest_snippets extends CompilerTestCase {

    public static void main(String[] args) {
        junit.textui.TestRunner.run(AMD64TranslatorTest_snippets.suite());
    }

    public static Test suite() {
        return new AMD64TranslatorTestSetup(new TestSuite(AMD64TranslatorTest_snippets.class)); // This performs the test
    }

    public AMD64TranslatorTest_snippets(String name) {
        super(name);
    }

    public void test() throws IOException, AssemblyException {
        AMD64Disassembler disassembler;
        for (Snippet snippet : Snippet.snippets()) {
            Trace.line(1, "snippet " + snippet.name + ":");
            final CPSTargetMethod targetMethod = (CPSTargetMethod) compilerTestSetup().translate(snippet.executable);
            targetMethod.traceBundle(IndentWriter.traceStreamWriter());
            disassembler = new AMD64Disassembler(targetMethod.codeStart().toLong(), InlineDataDecoder.createFrom(targetMethod.encodedInlineDataDescriptors()));
            final BufferedInputStream stream = new BufferedInputStream(new ByteArrayInputStream(targetMethod.code()));
            disassembler.scanAndPrint(stream, Trace.stream());
        }
    }
}