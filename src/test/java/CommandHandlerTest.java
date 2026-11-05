import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.beans.Transient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandHandlerTest{
    private Store store ;
    private CommandHandler handler;

    @BeforeEach
    void setUp(){
        store = new MapStore();
        handler = new CommandHandler(store);
    }

    @Test 
    void serverResponsePingPong(){
        byte[] pingResponse = handler.execute(List.of("ping"));
        String result = new String(
            pingResponse,
            StandardCharsets.UTF_8
        );
        assertEquals("+PONG\r\n",result);
    }

    @Test 
    void getNonExistentKeyReturnsNull(){
        byte[] response = handler.execute(List.of("GET","Missing-key"));
        String result = new String(
            response,
            StandardCharsets.UTF_8
        );
        assertEquals("$-1\r\n",result);
    }

    @Test 
    void SetAndGetKey(){
        byte[] setResponse = handler.execute(List.of("set","name","foo"));
        byte[] getResponse = handler.execute(List.of("get","name"));
        String result = new String(getResponse,StandardCharsets.UTF_8);
        assertEquals("$3\r\nfoo\r\n",result);
    }
    
    @Test 
    void deleteExistingKeyReturn1(){
        byte[] setResponse = handler.execute(List.of("set","name","foo"));
        byte[] delResponse = handler.execute(List.of("del","name"));
        String result = new String(
            delResponse,
            StandardCharsets.UTF_8
        );
        assertEquals(":1\r\n",result);
    }
    
    @Test 
    void deleteNonExistingKeyReturn0(){
        byte[] delResponse = handler.execute(List.of("del","name"));
        String result = new String(
            delResponse,
            StandardCharsets.UTF_8
        );
        assertEquals(":0\r\n",result);
    }
    
    @Test 
    void deleteMultipleExistingKeyReturnGreaterThan1(){
        handler.execute(List.of("set","name","foo"));
        handler.execute(List.of("set","name1","foo"));
        handler.execute(List.of("set","name2","foo"));
        byte[] delResponse = handler.execute(List.of("del","name","name2","name1"));
        String result = new String(
            delResponse,
            StandardCharsets.UTF_8
        );
        assertEquals(":3\r\n",result);
    }
    @Test 
    void unknownCommandErrorHandling(){
        byte[] response = handler.execute(List.of("Delete","name"));
        String result = new String(
            response,
            StandardCharsets.UTF_8
        );
        assertEquals("-ERR unknown command 'Delete'\r\n",result);
    }
    @Test 
    void wrongNumberOfArgumentsReturnError(){

     byte[] response = handler.execute(List.of("set","name","foo","123"));
        String result = new String(
            response,
            StandardCharsets.UTF_8
        );
        assertEquals("-ERR wrong number of arguments for 'set' command\r\n",result);
    }
}
