package gmail.bendavisnc.GeoPicasso.testing;


import com.google.common.base.Charsets;
import com.google.common.io.Files;
import gmail.bendavisnc.GeoPicasso.GeoPicassoRx;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Paths;


public class GeoTest {

    String testJsonData = "{\"name\":\"lady\",\"shapesAlongX\":\"10\",\"left\":270,\"top\":\"\",\"fills\":[],\"strokes\":[{\"color\":\"white\",\"opacity\":\"1\",\"width\":\"3\"}],\"shapes\":[0],\"background\":{\"color\":\"red\",\"opacity\":1},\"scale\":\"0.5\",\"width\":1920,\"height\":1080}";

    public GeoTest() {
        try {
//            String jsonPath = Paths.get(".").toAbsolutePath().normalize().toString() + "/json/test.json";
//            String jsonDataSerialized = Files.toString(new File(jsonPath), Charsets.UTF_8);
            JSONObject jsonData = new JSONObject(testJsonData);
            byte[] imageData = (byte[]) GeoPicassoRx.render(jsonData);
            String outPath = Paths.get(".").toAbsolutePath().normalize().toString() + "/out/test39.jpg";
            Files.write(imageData, new File(outPath));
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("NOT GOOOOD");
        }
        System.out.println("GOOOOD");


    }

    public static void main(String [] args) {
        new GeoTest();
    }
}