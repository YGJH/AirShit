package AirShit; // 或者您希望放置此例外類別的其他套件

public class NoFileSelectedException extends Exception { // 通常繼承自 Exception 或 RuntimeException

    public NoFileSelectedException(String message) {
        super(message);
    }

    public NoFileSelectedException(String message, Throwable cause) {
        super(message, cause);
    }
}