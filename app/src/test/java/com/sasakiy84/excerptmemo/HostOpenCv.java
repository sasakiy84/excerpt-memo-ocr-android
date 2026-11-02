package com.sasakiy84.excerptmemo;

final class HostOpenCv {
    private HostOpenCv() {}
    static void load() { System.load(System.getProperty("host.opencv.path")); }
}
