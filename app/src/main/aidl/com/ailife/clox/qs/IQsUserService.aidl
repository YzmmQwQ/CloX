package com.ailife.clox.qs;

interface IQsUserService {
    void destroy() = 16777114;
    void exit() = 1;
    boolean execShell(String command) = 2;
    String execShellOutput(String command) = 3;
}
