package cis5550.webserver;

import java.net.Socket;
import java.util.LinkedList;

class ConnectionQueue {
    private final LinkedList<Socket> queue = new LinkedList<>();

    public synchronized void enqueue(Socket s) {
        queue.addLast(s);
        notify();
    }

    public synchronized Socket dequeue() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();
        }
        return queue.removeFirst();
    }
}
