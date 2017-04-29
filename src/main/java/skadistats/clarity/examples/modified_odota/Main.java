package skadistats.clarity.examples.modified_odota;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 1);

        ArrayList<Callable<Void>> tasks = new ArrayList<>(args.length);

        for (final String file : args) {
            tasks.add(new Callable<Void>() {
                public Void call(){
                    try {
                        new Parse().run(file);
                    }
                    catch (IOException e) {
                        System.err.println("Could not find/read file: " + file);
                        e.printStackTrace();
                    }
                    return null;
                }
            });
        }
        executor.invokeAll(tasks, 20, TimeUnit.MINUTES);
        executor.shutdown();
    }
}
