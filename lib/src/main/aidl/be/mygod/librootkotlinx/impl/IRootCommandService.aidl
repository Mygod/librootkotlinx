package be.mygod.librootkotlinx.impl;

import be.mygod.librootkotlinx.impl.IRootCommandCallback;
import be.mygod.librootkotlinx.impl.RootCommandRequest;

oneway interface IRootCommandService {
    void execute(long id, in RootCommandRequest request, IRootCommandCallback callback);
    void executeOneWay(in RootCommandRequest request);
    void cancel(long id, IRootCommandCallback callback);
    void close(IRootCommandCallback callback);
}
