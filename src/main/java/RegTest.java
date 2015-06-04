import java.util.regex.*;

class RegTest
{
    public static void main(String[] args)
    {
        String txt="matrix(1.6528165,0,0,1.6528165,211.37009,-163.20412)";

        String re1="(matrix)";	// Word 1
        String re2="(\\()";	// Any Single Character 1
        String re3="([+-]?\\d*\\.\\d+)(?![-+0-9\\.])";	// Float 1
        String re4="(,)";	// Any Single Character 2
        String re5="(0)";	// Integer Number 1
        String re6="(,)";	// Any Single Character 3
        String re7="(0)";	// Integer Number 2
        String re8="(,)";	// Any Single Character 4
        String re9="([+-]?\\d*\\.\\d+)(?![-+0-9\\.])";	// Float 2
        String re10="(,)";	// Any Single Character 5
        String re11="([+-]?\\d*\\.\\d+)(?![-+0-9\\.])";	// Float 3
        String re12="(,)";	// Any Single Character 6
        String re13="([+-]?\\d*\\.\\d+)(?![-+0-9\\.])";	// Float 4
        String re14="(\\))";	// Any Single Character 7

        Pattern p = Pattern.compile(re1+re2+re3+re4+re5+re6+re7+re8+re9+re10+re11+re12+re13+re14,Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(txt);
        if (m.find())
        {
            String word1=m.group(1);
            String c1=m.group(2);
            String float1=m.group(3);
            String c2=m.group(4);
            String int1=m.group(5);
            String c3=m.group(6);
            String int2=m.group(7);
            String c4=m.group(8);
            String float2=m.group(9);
            String c5=m.group(10);
            String float3=m.group(11);
            String c6=m.group(12);
            String float4=m.group(13);
            String c7=m.group(14);
            System.out.print("("+word1.toString()+")"+"("+c1.toString()+")"+"("+float1.toString()+")"+"("+c2.toString()+")"+"("+int1.toString()+")"+"("+c3.toString()+")"+"("+int2.toString()+")"+"("+c4.toString()+")"+"("+float2.toString()+")"+"("+c5.toString()+")"+"("+float3.toString()+")"+"("+c6.toString()+")"+"("+float4.toString()+")"+"("+c7.toString()+")"+"\n");
        }
    }
}

//-----
