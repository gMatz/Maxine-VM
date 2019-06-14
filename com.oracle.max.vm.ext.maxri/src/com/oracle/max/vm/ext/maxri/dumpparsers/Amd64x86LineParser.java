package com.oracle.max.vm.ext.maxri.dumpparsers;

import com.oracle.max.vm.ext.maxri.ObjdumpLineParser;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Amd64x86LineParser extends ObjdumpLineParser{
    public Amd64x86LineParser(){    }

    public String parseLine(String line, long offsetFromStartOfcode, long codeStartAdress){
        String formatedLine="";
        try{
            String[] elements= line.split("\t");
            
            String adress=elements[0].replace(":","");
            String fullInstruction=elements[2];
            String instructionBytes="["+elements[1].toUpperCase()+"]";

           formatedLine+=adress+"\t"+ offsetFromStartOfcode+"\t";

            ArrayList<String> instructionParts=new ArrayList<>();
            for(String s:fullInstruction.split(" ")){
                if(!s.equals("")){
                    instructionParts.add(s);
                }
            }

            if(instructionParts.get(0).equals("call")){
                Pattern hexAddressPattern = Pattern.compile("0x([0-9a-fA-F]+)");
                Matcher m = hexAddressPattern.matcher(instructionParts.get(1));
                if(m.matches()){
                    long callOffset=Long.decode(instructionParts.get(1));
                    fullInstruction="call   "+"0x"+Long.toHexString( codeStartAdress+callOffset);
                }
            }

            formatedLine+=String.format("%-30s", fullInstruction);
            
            formatedLine+="\t"+instructionBytes;
        }
        catch(Exception e){
            formatedLine="ERROR for objdump line \""+line+"\"";
        }
        return formatedLine;//BASIC IMPLEMENTATION MAY NEED WORK
    }

}