/* Edge online speech protocol, https://github.com/rany2/edge-tts (LGPL-3.0).
 * Native Android implementation, 2026-10-22. No Python/runtime dependency. */
package com.jijian.translate;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

class EdgeSpeechClient {
    static final String HOST="speech.platform.bing.com";
    static final String USER_AGENT="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    static class Call {
        volatile boolean cancelled;private Socket socket;
        synchronized void attach(Socket value)throws IOException{if(cancelled){value.close();throw new IOException("已停止朗读。");}socket=value;}
        synchronized void cancel(){cancelled=true;try{if(socket!=null)socket.close();}catch(IOException ignored){}}
        void check()throws IOException{if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("已停止朗读。");}
    }
    static String voice(String language){switch(language){
        case "简体中文":return "zh-CN-XiaoxiaoNeural";case "繁體中文":return "zh-TW-HsiaoChenNeural";case "English":return "en-US-JennyNeural";
        case "日本語":return "ja-JP-NanamiNeural";case "한국어":return "ko-KR-SunHiNeural";case "Français":return "fr-FR-DeniseNeural";
        case "Deutsch":return "de-DE-KatjaNeural";case "Español":return "es-ES-ElviraNeural";case "Русский":return "ru-RU-SvetlanaNeural";
        default:throw new IllegalArgumentException("此语言暂未配置朗读声音。");}}
    static List<String> chunks(String text){
        StringBuilder clean=new StringBuilder();for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(i+1<text.length()&&Character.isLowSurrogate(text.charAt(i+1)))clean.append(c).append(text.charAt(++i));else clean.append(' ');}else if(Character.isLowSurrogate(c)||c==0xfffe||c==0xffff||c<32&&c!='\n'&&c!='\r'&&c!='\t')clean.append(' ');else clean.append(c);}
        String value=clean.toString().trim();List<String> parts=new ArrayList<>();for(int start=0;start<value.length();){int end=Math.min(start+900,value.length());if(end<value.length()){if(Character.isHighSurrogate(value.charAt(end-1)))end--;for(int i=end-1;i>start+300;i--)if("。！？.!?\n；; ".indexOf(value.charAt(i))>=0){end=i+1;break;}}parts.add(value.substring(start,end));start=end;}return parts;
    }
    static String ssml(String text,String voice,int rate){
        String escaped=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");int speed=rate==-20||rate==20?rate:0;
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='"+voice.substring(0,5)+"'><voice name='"+voice+"'><prosody pitch='+0Hz' rate='"+(speed>=0?"+":"")+speed+"%' volume='+0%'>"+escaped+"</prosody></voice></speak>";
    }
    static byte[] audioPayload(byte[] message)throws IOException{
        if(message.length<2)throw new IOException("语音响应不完整。");int length=(message[0]&255)*256+(message[1]&255);if(length>message.length-2)throw new IOException("语音响应头不完整。");
        String header=new String(message,2,length,StandardCharsets.UTF_8);if(!header.toLowerCase(Locale.ROOT).contains("path:audio"))throw new IOException("语音响应格式无法识别。");
        return java.util.Arrays.copyOfRange(message,2+length,message.length);
    }
    byte[] synthesize(String text,String language,int rate,Call call)throws Exception{
        String id=UUID.randomUUID().toString().replace("-","");String path="/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken="+EdgeToken.CLIENT_TOKEN+"&ConnectionId="+id+"&Sec-MS-GEC="+EdgeToken.generate(System.currentTimeMillis()/1000)+"&Sec-MS-GEC-Version="+EdgeToken.VERSION;
        // Platform trust store plus HTTPS hostname validation; no permissive trust manager.
        Socket tcp=new Socket();call.attach(tcp);SSLSocket tls=null;
        try{
            tcp.connect(new InetSocketAddress(HOST,443),10000);tcp.setSoTimeout(15000);call.check();
            tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(tcp,HOST,443,true);call.attach(tls);tls.setSoTimeout(15000);
            SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.startHandshake();call.check();
            InputStream in=tls.getInputStream();OutputStream out=new java.io.BufferedOutputStream(tls.getOutputStream());
            byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);String key=android.util.Base64.encodeToString(nonce,android.util.Base64.NO_WRAP);
            String request="GET "+path+" HTTP/1.1\r\nHost: "+HOST+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\nOrigin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\nUser-Agent: "+USER_AGENT+"\r\nCookie: muid="+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT)+";\r\nCache-Control: no-cache\r\nPragma: no-cache\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));out.flush();
            String response=readHeaders(in);String[] lines=response.split("\r\n");if(!lines[0].matches("HTTP/1\\.[01] 101(?: .*)?"))throw new IOException("Edge 朗读暂不可用，请检查网络和系统时间后重试。");
            String accept="",upgrade="",connection="";for(String line:lines){int colon=line.indexOf(':');if(colon<0)continue;String name=line.substring(0,colon).trim();String value=line.substring(colon+1).trim();if(name.equalsIgnoreCase("Sec-WebSocket-Accept"))accept=value;if(name.equalsIgnoreCase("Upgrade"))upgrade=value;if(name.equalsIgnoreCase("Connection"))connection=value;}
            String expected=android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),android.util.Base64.NO_WRAP);
            if(!expected.equals(accept)||!upgrade.equalsIgnoreCase("websocket")||!connection.toLowerCase(Locale.ROOT).contains("upgrade"))throw new IOException("语音连接校验失败。");
            SimpleDateFormat format=new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",Locale.US);format.setTimeZone(TimeZone.getTimeZone("UTC"));String stamp=format.format(new Date());
            send(out,1,("X-Timestamp:"+stamp+"\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n").getBytes(StandardCharsets.UTF_8));
            send(out,1,("X-RequestId:"+id+"\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:"+stamp+"Z\r\nPath:ssml\r\n\r\n"+ssml(text,voice(language),rate)).getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream audio=new ByteArrayOutputStream(),message=new ByteArrayOutputStream();int kind=0;long deadline=android.os.SystemClock.elapsedRealtime()+45000;
            while(true){
                call.check();if(android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("语音连接超时。");
                int first=one(in),second=one(in),opcode=first&15;boolean fin=(first&128)!=0;
                if((first&112)!=0||(second&128)!=0)throw new IOException("语音连接帧格式无效。");long length=second&127;
                if(length==126)length=(one(in)<<8)|one(in);else if(length==127){length=0;for(int i=0;i<8;i++){int b=one(in);if(i==0&&b!=0)throw new IOException("语音帧过大。");length=(length<<8)|b;if(length>1024*1024)throw new IOException("语音帧过大。");}}
                if(length>1024*1024||opcode>=8&&(!fin||length>125))throw new IOException("语音帧过大或不完整。");
                byte[] data=read(in,(int)length);if(opcode==8)throw new IOException("Edge 提前结束了语音连接。");if(opcode==9){send(out,10,data);continue;}if(opcode==10)continue;
                if(opcode==1||opcode==2){if(kind!=0)throw new IOException("语音消息交错。");kind=opcode;}else if(opcode!=0||kind==0)throw new IOException("语音消息格式无效。");
                message.write(data);if(message.size()>1024*1024)throw new IOException("语音消息过大。");if(!fin)continue;byte[] full=message.toByteArray();message.reset();int completedKind=kind;kind=0;
                if(completedKind==2){audio.write(audioPayload(full));if(audio.size()>4*1024*1024)throw new IOException("本段语音过长。");}
                else if(new String(full,StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("path:turn.end"))break;
            }
            call.check();if(audio.size()<100)throw new IOException("Edge 未返回语音，请稍后再试。");return audio.toByteArray();
        }finally{try{if(tls!=null)tls.close();else tcp.close();}catch(IOException ignored){}}
    }
    private static int one(InputStream in)throws IOException{int n=in.read();if(n<0)throw new EOFException("语音连接中断。");return n;}
    private static byte[] read(InputStream in,int length)throws IOException{byte[] data=new byte[length];for(int at=0;at<length;){int count=in.read(data,at,length-at);if(count<0)throw new EOFException("语音连接中断。");at+=count;}return data;}
    private static String readHeaders(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int tail=0;while(b.size()<16384){int c=one(in);b.write(c);tail=(tail<<8)|c;if(tail==0x0d0a0d0a)return new String(b.toByteArray(),StandardCharsets.US_ASCII);}throw new IOException("语音连接响应过大。");}
    static void send(OutputStream out,int opcode,byte[] data)throws IOException{
        byte[] mask=new byte[4];new SecureRandom().nextBytes(mask);out.write(128|opcode);int n=data.length;if(n<126)out.write(128|n);else if(n<65536){out.write(128|126);out.write(n>>>8);out.write(n);}else{out.write(128|127);for(int i=7;i>=0;i--)out.write((int)(((long)n)>>>(i*8)));}out.write(mask);for(int i=0;i<n;i++)out.write(data[i]^mask[i%4]);out.flush();
    }
}
