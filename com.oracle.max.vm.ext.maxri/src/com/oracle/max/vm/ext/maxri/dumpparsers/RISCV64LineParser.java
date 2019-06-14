package com.oracle.max.vm.ext.maxri.dumpparsers;

import com.oracle.max.vm.ext.maxri.ObjdumpLineParser;


public class RISCV64LineParser extends ObjdumpLineParser{
    public RISCV64LineParser(){

    }

    public String parseLine(String line, long offsetFromStartOfcode, long codeStartAdress){
        return line;//UNIMPLEMENTED
    }

}