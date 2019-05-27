package com.oracle.max.vm.ext.maxri;

import com.sun.max.lang.ISA;
import com.sun.max.platform.Platform;
import com.oracle.max.vm.ext.maxri.dumpparsers.*;


public abstract class ObjdumpLineParser {

    /**
     * Create a sutable instruction line parser for objdump
     * 
     */
    public static ObjdumpLineParser createParser(){
        final Platform platform = Platform.platform();
        if (platform.isa == ISA.AMD64) {
            return new Amd64x86LineParser();
        } else if (platform.isa == ISA.ARM) {
            return new ArmLineParser();
        } else if (platform.isa == ISA.Aarch64) {
            return new Aarch64LineParser();
        } else if (platform.isa == ISA.RISCV64) {
            return new RISCV64LineParser();
        } else {
            return new DefaultLineParser();
        }
    }

    /**
     * Parses an instruction line from objdump and returns a format acceptable <br>
     * for c1visualizer
     * 
     * @param line the instruction line from objdump
     * @param offsetFromStartOfcode the offset from the start of code
     */
    public abstract String parseLine(String line, long offsetFromStartOfcode);

}