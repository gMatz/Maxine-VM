/*
 * Copyright (c) 2010, 2011, Oracle and/or its affiliates. All rights reserved.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package test.output;

/**
 * Tests that class initialization is executed at the right time.
 */
public class ClassInitializationBarriers {

    static int a;
    static int b;
    static int c;
    static int d;

    public static void main(String[] args) {
        INVOKESTATIC.a();
        INVOKESTATIC.a();
//        new NEW();
//        System.out.println(GETSTATIC.field + PUTSTATIC.field);
        System.out.println("az = " + INVOKESTATIC.az);
        System.out.println("bz = " + INVOKESTATIC.bz);
//        System.out.println("b = " + b);
//        System.out.println("c = " + c);
//        System.out.println("d = " + d);
    }

    /** Tests class initialization barrier for INVOKESTATIC. */
    static class INVOKESTATIC {
        static int az;
        static int bz;
        static void a() {
        }
        static {
            a = 42;
        }
    }

    /** Tests class initialization barrier for NEW. */
    static class NEW {
        static {
            b = 42;
        }
    }

    /** Tests class initialization barrier for GETSTATIC. */
    static class GETSTATIC {
        static int field;
        static {
            c = 42;
        }
    }

    /** Tests class initialization barrier for PUTSTATIC. */
    static class PUTSTATIC {
        static int field;
        static {
            d = 42;
        }
    }
}
