package app.contracts;

/** Переходы между страницами приложения. */
public interface Navigator {
    enum Page { LOGIN, REGISTER, WELCOME }

    void show(Page page);
}
