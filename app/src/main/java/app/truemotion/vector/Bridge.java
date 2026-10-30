package app.truemotion.vector;
final class Bridge {
    static String message(Throwable error) {
        while (error.getCause()!=null && error.getCause()!=error) error=error.getCause();
        return error.getMessage()==null?error.toString():error.getMessage();
    }
}
