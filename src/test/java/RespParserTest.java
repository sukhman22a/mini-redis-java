
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RespParserTest{

    @Test 
    void readValidStringPing() throws IOException {
        String resp ="*1\r\n"+
                    "$4\r\n" +
                    "ping\r\n";
        ByteArrayInputStream input = new ByteArrayInputStream(
            resp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);
        List<String> result = parser.readCommand();
        assertEquals(List.of("ping"),result);
    }
    @Test
    void parseValidGetCommand() throws IOException{
        String resp = "*2\r\n"+
        "$3\r\n"+
        "get\r\n"+
        "$4\r\n"+
        "name\r\n";

        ByteArrayInputStream input = new ByteArrayInputStream(
            resp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);
        List<String> result = parser.readCommand();
        assertEquals(List.of("get","name"),result);
    }
    @Test 
    void parseValidSetCommand() throws IOException {
        String resp = "*3\r\n"+
                     "$3\r\n"+
                     "set\r\n"+
                     "$4\r\n"+
                     "name\r\n"+
                     "$3\r\n"+
                     "foo\r\n";
        
        ByteArrayInputStream input = new ByteArrayInputStream(
            resp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);
        List<String> result = parser.readCommand();
        assertEquals(List.of("set","name","foo"),result);
    }
    @Test 
    void parseEmptyBulkString() throws IOException {
        String resp = "*1\r\n"+
                     "$0\r\n"+
                     "\r\n";
        
        ByteArrayInputStream input = new ByteArrayInputStream(
            resp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);
        List<String> result = parser.readCommand();
        assertEquals(List.of(""),result);
    }
    @Test 
    void parseCommandWithSpaceInArgument() throws IOException {
        String resp = "*2\r\n"+
                     "$3\r\n"+
                     "get\r\n"+
                     "$11\r\n"+
                     "hello world\r\n";
        
        ByteArrayInputStream input = new ByteArrayInputStream(
            resp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);
        List<String> result = parser.readCommand();
        assertEquals(List.of("get","hello world"),result);
    }
    
    @Test 
    void parseCommandWithMultipleArgument() throws IOException {
        String resp = "*4\r\n"+
                     "$3\r\n"+
                     "del\r\n"+
                     "$11\r\n"+
                     "hello world\r\n"+
                     "$4\r\n"+
                     "name\r\n"+
                     "$5\r\n"+
                     "deptn\r\n";
        
        ByteArrayInputStream input = new ByteArrayInputStream(
            resp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);
        List<String> result = parser.readCommand();
        assertEquals(List.of("del","hello world","name","deptn"),result);
    }

    @Test 
    void parseMultipleCommandsFromSameStream() throws IOException {
        String resp1 = "*2\r\n"+
                     "$3\r\n"+
                     "get\r\n"+
                     "$11\r\n"+
                     "hello world\r\n";
        String resp2 = "*3\r\n"+
                     "$3\r\n"+
                     "set\r\n"+
                     "$4\r\n"+
                     "name\r\n"+
                     "$11\r\n"+
                     "hello world\r\n";
        String combinedResp = resp1 + resp2;
        ByteArrayInputStream input = new ByteArrayInputStream(
            combinedResp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);

        List<String> result1 = parser.readCommand();
        List<String> result2 = parser.readCommand();
        List<String> result3 = parser.readCommand();
        
        assertEquals(List.of("get","hello world"),result1);
        assertEquals(List.of("set","name","hello world"),result2);
        assertNull(result3);
    }
    @Test 
    void invalidHeaderThrowsIOException() {
        String resp = "?2\r\n"+
                        "$3\r\n"+
                        "get\r\n";
        ByteArrayInputStream input = new ByteArrayInputStream(
            resp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);
        assertThrows(IOException.class,()-> parser.readCommand());

    }

    @Test
    void invalidBulkStringHeaderThrowsIOException(){
        String resp = "*2\r\n"+
                        "?3\r\n"+
                        "get\r\n" +
                        "$2\r\n"+
                        "US\r\n";
        ByteArrayInputStream input = new ByteArrayInputStream(
            resp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);
        assertThrows(IOException.class,()->parser.readCommand());
    }     
    @Test 
    void invalidBulkStringLengthThrowsNumberFormatException() throws NumberFormatException{
        String resp = "*2\r\n"+
                        "$#\r\n"+
                        "get\r\n"+
                        "$4\r\n"+
                        "nice\r\n";
        ByteArrayInputStream input  = new ByteArrayInputStream(
            resp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);
        assertThrows(NumberFormatException.class,()->parser.readCommand());
    }
    @Test
    void invalidArrayLengthThrowsNumberFormatException() throws NumberFormatException{
        String resp = "*@\r\n"+
        "$3\r\n"+
        "get\r\n"+
        "$3\r\n"+
        "now\r\n";
        ByteArrayInputStream input = new ByteArrayInputStream(
            resp.getBytes(StandardCharsets.UTF_8)
        );
        RespParser parser = new RespParser(input);
        assertThrows(NumberFormatException.class,()->parser.readCommand());
    }
    @Test 
    void cleanDisconnectReturnNUll() throws IOException {
        String resp = "";
        ByteArrayInputStream input = new ByteArrayInputStream(resp.getBytes(StandardCharsets.UTF_8));
        RespParser parser = new RespParser(input);
        assertNull(parser.readCommand()) ;
    }
       
}