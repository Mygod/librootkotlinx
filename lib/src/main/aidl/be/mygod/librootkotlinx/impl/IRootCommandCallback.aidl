package be.mygod.librootkotlinx.impl;

import be.mygod.librootkotlinx.impl.RootCommandResponse;

oneway interface IRootCommandCallback {
    // One callback is shared for the session. The id multiplexes responses to avoid leaking per-command callback refs.
    void onResponse(long id, in RootCommandResponse response);
}
