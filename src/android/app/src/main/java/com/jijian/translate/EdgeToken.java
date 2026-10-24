/* Edge GEC v2, adapted from rany2/edge-tts drm.py (LGPL-3.0).
 * Copyright (c) rany <rany@riseup.net>. Java port: 2026-10-22.
 * See third_party/edge-tts and the corresponding source archive. */
package com.jijian.translate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class EdgeToken {
    static final String CLIENT_TOKEN="6A5AA1D4EAFF4E9FB37E23D68491D6F4", VERSION="2-143.0.3650.75";
    private static final int[] INITIAL={0x6A09E667,0xBB67AE85,0x3C6EF372,0xA54FF53A,0x510E527F,0x9B05688C,0x1F83D9AB,0x5BE0CD19};
    private static final int[] STATE={0xFD9FBEA2,0x91243CE7,0xBE499094,0xCD2E1E0A,0x6C006F49,0x04BD35AB,0xFEB0A7E4,0x03EFFBD2};
    private static final int[] SCHEDULE={0xEB24BF9E,0x1558691F,0xA0C09EE2,0x0CFA32BA,0xA622EE8C,0xD1DD9491,0xC0DD25A0,0xF9E70AB6,0xF0C31573,0x7058CE4E,0x7188E606,0x00E5AB33,0xD258B060,0x72766564};
    private static final int[] K={
        0x428A2F98,0x71374491,0xB5C0FBCF,0xE9B5DBA5,0x3956C25B,0x59F111F1,0x923F82A4,0xAB1C5ED5,
        0xD807AA98,0x12835B01,0x243185BE,0x550C7DC3,0x72BE5D74,0x80DEB1FE,0x9BDC06A7,0xC19BF174,
        0xE49B69C1,0xEFBE4786,0x0FC19DC6,0x240CA1CC,0x2DE92C6F,0x4A7484AA,0x5CB0A9DC,0x76F988DA,
        0x983E5152,0xA831C66D,0xB00327C8,0xBF597FC7,0xC6E00BF3,0xD5A79147,0x06CA6351,0x14292967,
        0x27B70A85,0x2E1B2138,0x4D2C6DFC,0x53380D13,0x650A7354,0x766A0ABB,0x81C2C92E,0x92722C85,
        0xA2BFE8A1,0xA81A664B,0xC24B8B70,0xC76C51A3,0xD192E819,0xD6990624,0xF40E3585,0x106AA070,
        0x19A4C116,0x1E376C08,0x2748774C,0x34B0BCB5,0x391C0CB3,0x4ED8AA4A,0x5B9CCA4F,0x682E6FF3,
        0x748F82EE,0x78A5636F,0x84C87814,0x8CC70208,0x90BEFFFA,0xA4506CEB,0xBEF9A3F7,0xC67178F2};
    private static final int[] COEFF={0xE82E91B1,0x44DE24C6,0x3BEB65BC,0xC89C5933,0x8D1EC211,0x314EF5D5,0xE92491B1,0xF0A5B9D5,0x06064CF3,0xDA79FA60,0xBED20560,0x89F9C9F8,0x35FA38C6,0xCB49E3C6};
    private static final int[] OFFSETS={0xB32700D0,0x88DFF51D,0x29514038,0xB328CBDF,0xD405B4D5,0x26E55F3C,0x607801C6,0x82B2C1FB,0xFA51A3DA,0x207CC464,0x3C3C74F3,0xD2D14B61,0xBB04A2C1,0xC5626167};
    private static int r(int x,int n){return(x>>>n)|(x<<(32-n));}
    private static int s0(int x){return r(x,7)^r(x,18)^(x>>>3);}
    private static int s1(int x){return r(x,17)^r(x,19)^(x>>>10);}
    private static int swap(int x){return r(x,16);}
    private static int be(byte[] b,int off){return ByteBuffer.wrap(b,off,4).getInt();}
    private static int le(byte[] b,int off){return ByteBuffer.wrap(b,off,4).order(ByteOrder.LITTLE_ENDIAN).getInt();}
    private static void expand(int[] w,int first){for(int i=first;i<64;i++)w[i]=s1(w[i-2])+w[i-7]+s0(w[i-15])+w[i-16];}
    private static int[] rounds(int[] s,int[] w,int first){
        int a=s[0],b=s[1],c=s[2],d=s[3],e=s[4],f=s[5],g=s[6],h=s[7];
        for(int i=first;i<64;i++){
            int t=h+(r(e,6)^r(e,11)^r(e,25))+(g^(e&(f^g)))+(first==14&&i==14?0xC9DC06A7:K[i])+w[i];
            int u=(r(a,2)^r(a,13)^r(a,22))+((a&b)|(c&(a|b)));
            h=g;g=f;f=e;e=d+t;d=c;c=b;b=a;a=t+u;
        }return new int[]{a,b,c,d,e,f,g,h};
    }
    static String hex(byte[] value){StringBuilder b=new StringBuilder();for(byte c:value)b.append("0123456789ABCDEF".charAt((c&255)>>>4)).append("0123456789ABCDEF".charAt(c&15));return b.toString();}
    static String generate(long unixSeconds){
        long seconds=unixSeconds+11644473600L;seconds-=seconds%300;
        byte[] data=(Long.toString(seconds*10000000L)+CLIENT_TOKEN).getBytes(StandardCharsets.US_ASCII);
        int[] w=new int[64];w[14]=(data[0]&255)<<16|(data[1]&255)<<8|(data[2]&255);w[15]=be(data,3);
        for(int i=16;i<30;i++){int v=SCHEDULE[i-16];if(i-2>=14)v+=s1(w[i-2])^(i==16?0x000B92C0:0);if(i-7>=14)v+=w[i-7];if(i-15>=14)v+=s0(w[i-15])^(i==29?0x059C0B80:0);w[i]=v;}
        w[30]=s1(w[28])+w[23]+s0(w[15])+w[14]+0xC9DC06A7-K[14];expand(w,31);
        int[] state=rounds(STATE,w,14);for(int i=0;i<8;i++)state[i]+=INITIAL[i];
        int length=57+data.length,zeros=(55-length)%64;if(zeros<0)zeros+=64;
        byte[] padded=new byte[data.length-7+1+zeros+8];System.arraycopy(data,7,padded,0,data.length-7);padded[data.length-7]=(byte)128;ByteBuffer.wrap(padded,padded.length-8,8).putLong(length*8L);
        for(int offset=0;offset<padded.length;offset+=64){for(int i=0;i<16;i++)w[i]=be(padded,offset+i*4);expand(w,16);int[] working=rounds(state,w,0);for(int i=0;i<8;i++)state[i]+=working[i];}
        ByteBuffer digestBuffer=ByteBuffer.allocate(32);for(int v:state)digestBuffer.putInt(v);byte[] digest=digestBuffer.array();
        int keyA=le(digest,0)|1,keyB=le(digest,4)|1;
        byte[] words=new byte[((data.length+1+7)/8)*8];System.arraycopy(data,0,words,1,data.length);
        int count=14+words.length/4;int[] terms=new int[count],offsets=new int[count];
        for(int i=0;i<14;i++){terms[i]=COEFF[i]*(i%2==0?keyA:keyB)*0x82893933;offsets[i]=OFFSETS[i];}
        for(int i=0;i<words.length/4;i++){int v=le(words,i*4);terms[i+14]=v*(i%2==0?keyA:keyB);offsets[i+14]=swap(v)*(i%2==0?0xEF0569FB:0xC31713DB);if(i==0){terms[14]+=0xF2F90B1A*keyA*0x82893933;offsets[14]+=0x0B1A0000;}}
        int swapped=0,last=0,current=0,acc=0;
        for(int i=0;i<count;i+=2){
            int q=swap(offsets[i]+terms[i])*0x76F50F34;swapped+=swap(q)*0xC3EFEA97+q;
            q=swap(offsets[i+1]+terms[i+1])*0x1881CE3C;last=swap(q)*0x35BD1EC9+q;swapped+=last;
            current=current*keyA+terms[i];current=swap(current)*0xCF98B111;current=swap(current)*0x87085B9F;current=swap(current)*0x12CEB96D;current=swap(current)*0x257E1D83;acc+=current;
            int t=current*keyB+terms[i+1];t=swap(t)*0xA27416F5;t=swap(t)*0xD38396FF;current=swap(swap(t)*0x7C932B89)*0xBFA49F69;acc+=current;
        }
        byte[] cs=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(last^current).putInt(swapped^acc).array();return hex(cs)+"-"+hex(digest);
    }
}
