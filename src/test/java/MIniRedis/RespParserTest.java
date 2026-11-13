package MiniRedis;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RespParserTest{

    @Test 
    void readValidStringPing() throws IllegalArgumentException {
        String resp ="*1\r\n"+
                    "$4\r\n" +
                    "ping\r\n";
        ByteBuffer input = ByteBuffer.wrap(
            resp.getBytes(StandardCharsets.UTF_8)
        );

        List<String> result = RespParser.tryParse(input);
        assertEquals(List.of("ping"),result);
    }
    @Test
    void parseValidGetCommand() throws IllegalArgumentException{
        String resp = "*2\r\n"+
        "$3\r\n"+
        "get\r\n"+
        "$4\r\n"+
        "name\r\n";

        ByteBuffer input = ByteBuffer.wrap(
            resp.getBytes(StandardCharsets.UTF_8)
        );

        List<String> result = RespParser.tryParse(input);
        assertEquals(List.of("get","name"),result);
    }
    @Test 
    void parseValidSetCommand() throws IllegalArgumentException {
        String resp = "*3\r\n"+
                     "$3\r\n"+
                     "set\r\n"+
                     "$4\r\n"+
                     "name\r\n"+
                     "$3\r\n"+
                     "foo\r\n";
        
        ByteBuffer input = ByteBuffer.wrap(
            resp.getBytes(StandardCharsets.UTF_8)
        );

        List<String> result = RespParser.tryParse(input);
        assertEquals(List.of("set","name","foo"),result);
    }
    @Test 
    void parseEmptyBulkString() throws IllegalArgumentException {
        String resp = "*1\r\n"+
                     "$0\r\n"+
                     "\r\n";
        
        ByteBuffer input = ByteBuffer.wrap(
            resp.getBytes(StandardCharsets.UTF_8)
        );

        List<String> result = RespParser.tryParse(input);
        assertEquals(List.of(""),result);
    }
    @Test 
    void parseCommandWithSpaceInArgument() throws IllegalArgumentException {
        String resp = "*2\r\n"+
                     "$3\r\n"+
                     "get\r\n"+
                     "$11\r\n"+
                     "hello world\r\n";
        
        ByteBuffer input = ByteBuffer.wrap(
            resp.getBytes(StandardCharsets.UTF_8)
        );

        List<String> result = RespParser.tryParse(input);
        assertEquals(List.of("get","hello world"),result);
    }
    
    @Test 
    void parseCommandWithMultipleArgument() throws IllegalArgumentException {
        String resp = "*4\r\n"+
                     "$3\r\n"+
                     "del\r\n"+
                     "$11\r\n"+
                     "hello world\r\n"+
                     "$4\r\n"+
                     "name\r\n"+
                     "$5\r\n"+
                     "deptn\r\n";
        
        ByteBuffer input = ByteBuffer.wrap(
            resp.getBytes(StandardCharsets.UTF_8)
        );

        List<String> result = RespParser.tryParse(input);
        assertEquals(List.of("del","hello world","name","deptn"),result);
    }

    @Test 
    void parseMultipleCommandsFromSameStream() throws IllegalArgumentException {
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
        ByteBuffer input = ByteBuffer.wrap(
            combinedResp.getBytes(StandardCharsets.UTF_8)
        );


        List<String> result1 = RespParser.tryParse(input);
        List<String> result2 = RespParser.tryParse(input);
        List<String> result3 = RespParser.tryParse(input);
        
        assertEquals(List.of("get","hello world"),result1);
        assertEquals(List.of("set","name","hello world"),result2);
        assertNull(result3);
    }
    @Test 
    void invalidHeaderThrowsllegalArgumentException() {
        String resp = "?2\r\n"+
                        "$3\r\n"+
                        "get\r\n";
        ByteBuffer input = ByteBuffer.wrap(
            resp.getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(IllegalArgumentException.class,()-> RespParser.tryParse(input));

    }

    @Test
    void invalidBulkStringHeaderThrowsllegalArgumentException(){
        String resp = "*2\r\n"+
                        "?3\r\n"+
                        "get\r\n" +
                        "$2\r\n"+
                        "US\r\n";
        ByteBuffer input = ByteBuffer.wrap(
            resp.getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(IllegalArgumentException.class,()->RespParser.tryParse(input));
    }     
    @Test 
    void invalidBulkStringLengthThrowsNumberFormatException() throws NumberFormatException{
        String resp = "*2\r\n"+
                        "$#\r\n"+
                        "get\r\n"+
                        "$4\r\n"+
                        "nice\r\n";
        ByteBuffer input  = ByteBuffer.wrap(
            resp.getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(NumberFormatException.class,()->RespParser.tryParse(input));
    }
    @Test
    void invalidArrayLengthThrowsNumberFormatException() throws NumberFormatException{
        String resp = "*@\r\n"+
        "$3\r\n"+
        "get\r\n"+
        "$3\r\n"+
        "now\r\n";
        ByteBuffer input = ByteBuffer.wrap(
            resp.getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(NumberFormatException.class,()->RespParser.tryParse(input));
    }
    @Test 
    void cleanDisconnectReturnNUll() throws IllegalArgumentException {
        String resp = "";
        ByteBuffer input = ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8));

        assertNull(RespParser.tryParse(input)) ;
    }
       
}