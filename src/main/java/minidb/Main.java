package minidb;

import minidb.core.Database;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        try {
            Database database = new Database(Paths.get("data", "database.db"));
            Scanner scanner = new Scanner(System.in);
            System.out.println("MiniDB. Enter SQL query or EXIT.");
            while (true) {
                System.out.print("minidb> ");
                if (!scanner.hasNextLine()) break;
                String query = scanner.nextLine().trim();
                if (query.equalsIgnoreCase("exit") || query.equalsIgnoreCase("quit")) break;
                if (query.isEmpty()) continue;
                try {
                    System.out.println(database.execute(query));
                } catch (Exception e) {
                    System.out.println("Error: " + e.getMessage());
                }
            }
            scanner.close();
        } catch (IOException e) {
            System.out.println("Cannot open database: " + e.getMessage());
        }
    }
}
