package com.xingchen.huibiao.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 关联键归一化：跨系统工号的几种写法必须能对上，否则两份数据明明是同一批人，
class TextUtilsTest {

    @Test
    void 不同系统的工号写法归一到同一个键() {
        assertEquals(TextUtils.key("E10001"), TextUtils.key("010001"), "HR 的字母前缀要能对上 ERP 的补零写法");
        assertEquals("10001", TextUtils.key("E10001"));
        assertEquals("10001", TextUtils.key("010001"));
        assertEquals("10001", TextUtils.key("e-010001"), "小写、带连字符也要归一");
        assertEquals("10001", TextUtils.key(" 010001 "), "首尾空格要去掉");
        assertEquals("1", TextUtils.key("0001"), "前导零全部去掉");
    }

    @Test
    void 含中文或非数字的编号原样保留() {
        assertEquals("张三", TextUtils.key("张三"));
        assertEquals("a001x", TextUtils.key("A001x"), "不是纯数字就按原文比较，不去臆造编号");
        assertEquals("", TextUtils.key(null));
        assertEquals("", TextUtils.key("   "));
    }
}
