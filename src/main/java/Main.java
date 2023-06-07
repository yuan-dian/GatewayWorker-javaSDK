import utils.WebSocketUtil;

/**
 * @author 原点
 * @date 2023/6/6 09:42
 */
public class Main {
    public static void main(String[] args) throws Exception {
        WebSocketUtil.sendToClient("c1a807ca0b57000008aa","111");
        WebSocketUtil.bindUid("c1a807ca0b55000005c3","111");
//
        WebSocketUtil.setRegisterHost("127.0.0.1");
        WebSocketUtil.setRegisterPort((short) 1238);
//        WebSocketUtil.setSecretKey("1234");
        WebSocketUtil.sendToUid("111","1111");
    }
}
