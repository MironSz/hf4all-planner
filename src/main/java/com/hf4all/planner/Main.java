package com.hf4all.planner;

import com.hf4all.planner.config.Config;
import com.hf4all.planner.server.PlannerServer;

public class Main {

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Config.serverPort();
        PlannerServer server = new PlannerServer(port);
        server.start();
    }
}
