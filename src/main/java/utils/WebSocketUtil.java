package utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * @author 原点
 * @date 2023/6/5 15:01
 */
public class WebSocketUtil {

    /*** 发给gateway的向单个用户发送数据 */
    private final static Byte CMD_SEND_TO_ONE = 5;

    /*** client_id 绑定到 uid */
    private final static Byte CMD_BIND_UID = 12;
    /*** client_id 解绑 uid */
    private final static Byte CMD_UNBIND_UID = 13;
    /*** 向uid发送数据 */
    private final static Byte CMD_SEND_TO_UID = 14;
    /*** GatewayClient连接gateway事件 */
    private final static int CMD_GATEWAY_CLIENT_CONNECT = 202;
    /*** 签名 */
    private static String SECRETKEY = "";
    /*** 注册服务地址 */
    private static String registerHost = "127.0.0.1";
    /*** 注册服务端口号 */
    private static Short registerPort = 1238;
    /*** 网关缓存地址 */
    private static List<String> addressesCache = null;
    /*** 缓存网关最后更新时间 */
    private static Long lastUpdate = 0L;
    /*** 网关缓存时间 */
    private final static int expirationTime = 1000_000; // 超时时间，单位为毫秒
    /*** 线程池 */
    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(16,
            30,
            1000L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>()
    );


    /**
     * 设置加密key
     *
     * @param secretkey
     */
    public static void setSecretKey(String secretkey) {
        WebSocketUtil.SECRETKEY = secretkey;
    }

    /**
     * 设置注册地址
     *
     * @param registerHost
     */
    public static void setRegisterHost(String registerHost) {
        WebSocketUtil.registerHost = registerHost;
    }

    /**
     * 设备注册地址端口号
     *
     * @param registerPort
     */
    public static void setRegisterPort(Short registerPort) {
        WebSocketUtil.registerPort = registerPort;
    }


    /**
     * 给客户端发消息
     *
     * @param clientId
     * @param message
     * @return
     * @throws Exception
     */
    public static boolean sendToClient(String clientId, String message) throws Exception {
        return sendCmdAndMessageToClient(clientId, CMD_SEND_TO_ONE, message, null);
    }

    /**
     * 绑定用户id
     *
     * @param clientId
     * @param uid
     * @return
     * @throws Exception
     */
    public static boolean bindUid(String clientId, String uid) throws Exception {
        return sendCmdAndMessageToClient(clientId, CMD_BIND_UID, "", uid);
    }

    /**
     * 解绑
     *
     * @param clientId
     * @param uid
     * @return
     * @throws Exception
     */
    public static boolean unbindUid(String clientId, String uid) throws Exception {
        return sendCmdAndMessageToClient(clientId, CMD_UNBIND_UID, "", uid);
    }

    /**
     * 给用户id发消息
     *
     * @param uid
     * @param message
     * @return
     */
    public static boolean sendToUid(String uid, String message) {
        Encode encode = new Encode();
        encode.setCmd(CMD_SEND_TO_UID);
        encode.setBody(message);
        encode.setExt("[\"" + uid + "\"]");

        return sendToAllGateway(encode);
    }

    /**
     * 给指定客户端网关发消息
     *
     * @param clientId
     * @param cmd
     * @param message
     * @param ext
     * @return
     * @throws Exception
     */
    protected static boolean sendCmdAndMessageToClient(String clientId, Byte cmd, String message, String ext) throws Exception {
        Encode addressData = clientIdToAddress(clientId);
        String host = long2ip(addressData.getLocalIp());
        Short port = addressData.getLocalPort();
        Integer connectionId = addressData.getConnectionId();
        Encode encode = new Encode();
        encode.setCmd(cmd);
        encode.setConnectionId(connectionId);
        encode.setBody(message);
        if (ext != null) {
            encode.setExt(ext);
        }
        return sendToGateway(host, port, encode);
    }

    /**
     * clientId转地址
     *
     * @param clientId
     * @return
     * @throws Exception
     */
    protected static Encode clientIdToAddress(String clientId) throws Exception {
        if (clientId.length() != 20) {
            throw new Exception("client_id " + clientId + " is invalid");
        }
        return unpack(clientId);
    }

    /**
     * 给网关发消息
     *
     * @param host
     * @param port
     * @param encode
     * @return
     */
    protected static boolean sendToGateway(String host, Short port, Encode encode) {
        return sendBufferToGateway(host, port, encode(encode));
    }

    /**
     * 打包请求数据
     *
     * @param data
     * @return
     */
    protected static byte[] encode(Encode data) {
        byte[] body = data.getBody().getBytes(StandardCharsets.UTF_8);
        byte[] ext = data.getExt().getBytes(StandardCharsets.UTF_8);
        int ext_len = data.getExt().length();
        int package_len = 28 + ext_len + body.length;
        ByteBuffer buffer = ByteBuffer.allocate(package_len);
        buffer.putInt(package_len);
        buffer.put(data.getCmd());
        buffer.putInt(data.getLocalIp());
        buffer.putShort(data.getLocalPort());
        buffer.putInt(data.getClientIp());
        buffer.putShort(data.getClientPort());
        buffer.putInt(data.getConnectionId());
        buffer.put((byte) 1);
        buffer.putShort(data.getGatewayPort());
        buffer.putInt(ext_len);
        buffer.put(ext);
        buffer.put(body);
        return buffer.array();
    }

    /**
     * 发送请求
     *
     * @param host
     * @param port
     * @param message
     * @return
     */
    protected static boolean sendBufferToGateway(String host, Short port, byte[] message) {
        try {
            // 创建 Socket 对象
            Socket socket = new Socket(host, port);
            socket.setSoTimeout(1000); // 设置超时时间
            // 获取输出流
            OutputStream os = socket.getOutputStream();
            if (SECRETKEY.length() != 0) {
                byte[] authBuffer = generateAuthBuffer();
                byte[] buff = new byte[authBuffer.length + message.length];
                System.arraycopy(authBuffer, 0, buff, 0, authBuffer.length);
                System.arraycopy(message, 0, buff, authBuffer.length, message.length);
                message = buff;
            }
            os.write(message);
            // 关闭连接
            socket.close();
            return true;
        } catch (IOException e) {
            System.err.printf("Error occurred while sending message to %s:%s %s\n", host, port, e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    /**
     * 生成加密体
     *
     * @return
     */
    protected static byte[] generateAuthBuffer() {
        Encode encode = new Encode();
        encode.setCmd((byte) CMD_GATEWAY_CLIENT_CONNECT);
        encode.setBody("{\"secret_key\":\"" + SECRETKEY + "\"}");
        return encode(encode);
    }

    /**
     * 给全部网关发送消息
     *
     * @param encode
     * @return
     */
    protected static boolean sendToAllGateway(Encode encode) {
        byte[] buffer = encode(encode);
        try {
            List<String> allAddresses = getAllGatewayAddressesFromRegister();

            if (allAddresses == null) {
                throw new IOException("Gateway::getAllGatewayAddressesFromRegister() with registerAddress is empty");
            }
            for (String address : allAddresses) {
                String[] split = address.split(":", 2);
                executor.execute(() -> sendBufferToGateway(split[0], Short.parseShort(split[1]), buffer));
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 从注册机器获取全部的网关地址
     *
     * @return
     * @throws IOException
     */
    protected static List<String> getAllGatewayAddressesFromRegister() throws IOException {

        long timeNow = System.currentTimeMillis();

        // 如果缓存不存在，或者已经过期，则重新从服务器获取地址
        if (addressesCache == null || timeNow - lastUpdate > expirationTime) {
            // 创建 Socket 对象
            Socket socket = new Socket(registerHost, registerPort);
            socket.setSoTimeout(1000); // 设置超时时间

            // 获取输出流并发送数据
            OutputStream os = socket.getOutputStream();
            String request = "{\"event\":\"worker_connect\",\"secret_key\":\"" + SECRETKEY + "\"}\n";
            os.write(request.getBytes());
            os.flush();

            // 获取输入流并读取数据
            InputStream is = socket.getInputStream();
            byte[] buf = new byte[1024];
            int len = is.read(buf);
            String response = new String(buf, 0, len);
            // 关闭连接
            socket.close();

            // 解析 JSON 数据
            List<String> addresses = new ArrayList<>();
            lastUpdate = System.currentTimeMillis();
            if (response != null && response.length() > 0) {
                try {
                    JSONObject json = JSON.parseObject(response);

                    if (json.containsKey("addresses")) {
                        JSONArray jsonAddresses = json.getJSONArray("addresses");

                        for (int i = 0; i < jsonAddresses.size(); i++) {
                            String address = jsonAddresses.getString(i);
                            addresses.add(address);
                        }
                    }
                } catch (Exception e) {
                    throw new IOException("getAllGatewayAddressesFromRegister failed. server return: " + response);
                }
            }
            // 更新缓存
            if (!addresses.isEmpty()) {
                addressesCache = addresses;
            }
        }
        // 返回结果
        return addressesCache;
    }

    /**
     * 解包
     *
     * @param hexString
     * @return
     */
    protected static Encode unpack(String hexString) {
        // 将十六进制字符串转换成二进制字符数组
        byte[] data = hexStringToByteArray(hexString);

        // 读取数据到变量中
        Encode encode = new Encode();
        int index = 0;
        encode.setLocalIp((int) readUnsignedInt(data, index));
        index += 4;
        encode.setLocalPort((short) readUnsignedShort(data, index));
        index += 2;
        encode.setConnectionId((int) readUnsignedInt(data, index));
        return encode;
    }

    /**
     * 将十六进制字符串转换成二进制字符数组
     *
     * @param hexString
     * @return
     */
    protected static byte[] hexStringToByteArray(String hexString) {
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * 读取无符号int
     *
     * @param data
     * @param index
     * @return
     */
    protected static long readUnsignedInt(byte[] data, int index) {
        return ((long) data[index] & 0xFF) << 24 | ((long) data[index + 1] & 0xFF) << 16
                | ((long) data[index + 2] & 0xFF) << 8 | ((long) data[index + 3] & 0xFF);
    }

    /**
     * 读取无符号Short
     *
     * @param data
     * @param index
     * @return
     */
    protected static int readUnsignedShort(byte[] data, int index) {
        return (data[index] & 0xFF) << 8 | (data[index + 1] & 0xFF);
    }

    /**
     * long 转 ip
     *
     * @param ip
     * @return
     */
    protected static String long2ip(long ip) {
        StringBuilder sb = new StringBuilder(15);
        for (int i = 0; i < 4; i++) {
            sb.insert(0, ip & 0xff);
            if (i < 3) {
                sb.insert(0, '.');
            }
            ip = ip >> 8;
        }
        return sb.toString();
    }

    @Getter
    @Setter
    public static class Encode implements Serializable {
        private static final long serialVersionUID = 801205930039755892L;
        private Byte cmd;
        private Integer localIp;
        private Short localPort;
        private Integer clientIp;
        private Short clientPort;
        private Integer connectionId;
        private Byte flag;
        private Short gatewayPort;
        private String ext;
        private String body;

        public Encode() {
            this.cmd = 0;
            this.localIp = 0;
            this.localPort = 0;
            this.clientIp = 0;
            this.clientPort = 0;
            this.connectionId = 0;
            this.flag = 0;
            this.gatewayPort = 0;
            this.ext = "";
            this.body = "";
        }
    }
}
