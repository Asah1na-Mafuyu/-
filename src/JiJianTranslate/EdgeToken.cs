// Edge GEC v2, adapted from rany2/edge-tts drm.py (LGPL-3.0).
// Copyright (c) rany <rany@riseup.net>. C# port: 2026-10-22.
// See third_party/edge-tts and the corresponding source archive.
using System;
using System.Buffers.Binary;
using System.Globalization;
using System.Text;

namespace JiJianTranslate;

internal static class EdgeToken
{
    internal const string ClientToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    internal const string Version = "2-143.0.3650.75";
    private static readonly uint[] Initial = { 0x6A09E667,0xBB67AE85,0x3C6EF372,0xA54FF53A,0x510E527F,0x9B05688C,0x1F83D9AB,0x5BE0CD19 };
    private static readonly uint[] State = { 0xFD9FBEA2,0x91243CE7,0xBE499094,0xCD2E1E0A,0x6C006F49,0x04BD35AB,0xFEB0A7E4,0x03EFFBD2 };
    private static readonly uint[] Schedule = { 0xEB24BF9E,0x1558691F,0xA0C09EE2,0x0CFA32BA,0xA622EE8C,0xD1DD9491,0xC0DD25A0,0xF9E70AB6,0xF0C31573,0x7058CE4E,0x7188E606,0x00E5AB33,0xD258B060,0x72766564 };
    private static readonly uint[] K = {
        0x428A2F98,0x71374491,0xB5C0FBCF,0xE9B5DBA5,0x3956C25B,0x59F111F1,0x923F82A4,0xAB1C5ED5,
        0xD807AA98,0x12835B01,0x243185BE,0x550C7DC3,0x72BE5D74,0x80DEB1FE,0x9BDC06A7,0xC19BF174,
        0xE49B69C1,0xEFBE4786,0x0FC19DC6,0x240CA1CC,0x2DE92C6F,0x4A7484AA,0x5CB0A9DC,0x76F988DA,
        0x983E5152,0xA831C66D,0xB00327C8,0xBF597FC7,0xC6E00BF3,0xD5A79147,0x06CA6351,0x14292967,
        0x27B70A85,0x2E1B2138,0x4D2C6DFC,0x53380D13,0x650A7354,0x766A0ABB,0x81C2C92E,0x92722C85,
        0xA2BFE8A1,0xA81A664B,0xC24B8B70,0xC76C51A3,0xD192E819,0xD6990624,0xF40E3585,0x106AA070,
        0x19A4C116,0x1E376C08,0x2748774C,0x34B0BCB5,0x391C0CB3,0x4ED8AA4A,0x5B9CCA4F,0x682E6FF3,
        0x748F82EE,0x78A5636F,0x84C87814,0x8CC70208,0x90BEFFFA,0xA4506CEB,0xBEF9A3F7,0xC67178F2 };
    private static readonly uint[] Coeff = { 0xE82E91B1,0x44DE24C6,0x3BEB65BC,0xC89C5933,0x8D1EC211,0x314EF5D5,0xE92491B1,0xF0A5B9D5,0x06064CF3,0xDA79FA60,0xBED20560,0x89F9C9F8,0x35FA38C6,0xCB49E3C6 };
    private static readonly uint[] Offsets = { 0xB32700D0,0x88DFF51D,0x29514038,0xB328CBDF,0xD405B4D5,0x26E55F3C,0x607801C6,0x82B2C1FB,0xFA51A3DA,0x207CC464,0x3C3C74F3,0xD2D14B61,0xBB04A2C1,0xC5626167 };
    private static uint R(uint x, int n) => (x >> n) | (x << (32 - n));
    private static uint S0(uint x) => R(x,7) ^ R(x,18) ^ (x >> 3);
    private static uint S1(uint x) => R(x,17) ^ R(x,19) ^ (x >> 10);
    private static uint Swap(uint x) => R(x,16);
    private static void Expand(uint[] w, int first) { unchecked { for (int i=first;i<64;i++) w[i]=S1(w[i-2])+w[i-7]+S0(w[i-15])+w[i-16]; } }
    private static uint[] Rounds(uint[] s, uint[] w, int first)
    {
        unchecked {
            uint a=s[0],b=s[1],c=s[2],d=s[3],e=s[4],f=s[5],g=s[6],h=s[7];
            for(int i=first;i<64;i++) {
                uint t=h+(R(e,6)^R(e,11)^R(e,25))+(g^(e&(f^g)))+(first==14&&i==14?0xC9DC06A7:K[i])+w[i];
                uint u=(R(a,2)^R(a,13)^R(a,22))+((a&b)|(c&(a|b)));
                h=g;g=f;f=e;e=d+t;d=c;c=b;b=a;a=t+u;
            }
            return new[]{a,b,c,d,e,f,g,h};
        }
    }
    internal static string Generate(long unixSeconds)
    {
        unchecked {
            long seconds=unixSeconds+11644473600L;seconds-=seconds%300;
            byte[] data=Encoding.ASCII.GetBytes((seconds*10000000L).ToString(CultureInfo.InvariantCulture)+ClientToken);
            var w=new uint[64];w[14]=(uint)(data[0]<<16|data[1]<<8|data[2]);w[15]=BinaryPrimitives.ReadUInt32BigEndian(data.AsSpan(3));
            for(int i=16;i<30;i++) { uint v=Schedule[i-16];if(i-2>=14)v+=S1(w[i-2])^(i==16?0x000B92C0u:0);if(i-7>=14)v+=w[i-7];if(i-15>=14)v+=S0(w[i-15])^(i==29?0x059C0B80u:0);w[i]=v; }
            w[30]=S1(w[28])+w[23]+S0(w[15])+w[14]+0xC9DC06A7-K[14];Expand(w,31);
            var state=Rounds(State,w,14);for(int i=0;i<8;i++)state[i]+=Initial[i];
            int length=57+data.Length;int zeros=(55-length)%64;if(zeros<0)zeros+=64;
            byte[] padded=new byte[data.Length-7+1+zeros+8];Array.Copy(data,7,padded,0,data.Length-7);padded[data.Length-7]=128;
            BinaryPrimitives.WriteUInt64BigEndian(padded.AsSpan(padded.Length-8),(ulong)length*8);
            for(int offset=0;offset<padded.Length;offset+=64) { for(int i=0;i<16;i++)w[i]=BinaryPrimitives.ReadUInt32BigEndian(padded.AsSpan(offset+i*4));Expand(w,16);var working=Rounds(state,w,0);for(int i=0;i<8;i++)state[i]+=working[i]; }
            var digest=new byte[32];for(int i=0;i<8;i++)BinaryPrimitives.WriteUInt32BigEndian(digest.AsSpan(i*4),state[i]);
            uint keyA=BinaryPrimitives.ReadUInt32LittleEndian(digest)|1,keyB=BinaryPrimitives.ReadUInt32LittleEndian(digest.AsSpan(4))|1;
            byte[] words=new byte[((data.Length+1+7)/8)*8];Array.Copy(data,0,words,1,data.Length);
            int count=14+words.Length/4;var terms=new uint[count];var offsets=new uint[count];
            for(int i=0;i<14;i++){terms[i]=Coeff[i]*(i%2==0?keyA:keyB)*0x82893933;offsets[i]=Offsets[i];}
            for(int i=0;i<words.Length/4;i++) {uint v=BinaryPrimitives.ReadUInt32LittleEndian(words.AsSpan(i*4));terms[i+14]=v*(i%2==0?keyA:keyB);offsets[i+14]=Swap(v)*(i%2==0?0xEF0569FBu:0xC31713DBu);if(i==0){terms[14]+=0xF2F90B1A*keyA*0x82893933;offsets[14]+=0x0B1A0000;}}
            uint swapped=0,last=0,current=0,acc=0;
            for(int i=0;i<count;i+=2) {
                uint q=Swap(offsets[i]+terms[i])*0x76F50F34;swapped+=Swap(q)*0xC3EFEA97+q;
                q=Swap(offsets[i+1]+terms[i+1])*0x1881CE3C;last=Swap(q)*0x35BD1EC9+q;swapped+=last;
                current=current*keyA+terms[i];current=Swap(current)*0xCF98B111;current=Swap(current)*0x87085B9F;current=Swap(current)*0x12CEB96D;current=Swap(current)*0x257E1D83;acc+=current;
                uint t=current*keyB+terms[i+1];t=Swap(t)*0xA27416F5;t=Swap(t)*0xD38396FF;current=Swap(Swap(t)*0x7C932B89)*0xBFA49F69;acc+=current;
            }
            byte[] cs=new byte[8];BinaryPrimitives.WriteUInt32LittleEndian(cs,last^current);BinaryPrimitives.WriteUInt32LittleEndian(cs.AsSpan(4),swapped^acc);
            return Convert.ToHexString(cs)+"-"+Convert.ToHexString(digest);
        }
    }
}
