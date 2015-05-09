/**
 * Created by ben on 4/25/15.
 */
public class testingPurposes {

    testingPurposes() {
        System.out.println(re1+re2+re3+re4+re5+re6);
    }

    String re1="(translate)";	// Word 1
    String re2="(\\()";	// Any Single Character 1
    String re3="([+-]?\\d*\\.\\d+)(?![-+0-9\\.])";	// Float 1
    String re4="(,)";	// Any Single Character 2
    String re5="([+-]?\\d*\\.\\d+)(?![-+0-9\\.])";	// Float 2
    String re6="(\\))";	// Any Single Character 3

    public static void main(String [] args) {
        new testingPurposes();
    }
}
