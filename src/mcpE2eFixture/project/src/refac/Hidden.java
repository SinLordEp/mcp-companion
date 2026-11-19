package refac;

/** Package-private: moving UsesHidden out of refac is a conflict (mc_move e2e). */
class Hidden {
    static int secret() {
        return 42;
    }
}
