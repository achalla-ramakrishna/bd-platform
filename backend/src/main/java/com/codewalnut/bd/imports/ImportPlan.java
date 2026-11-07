package com.codewalnut.bd.imports;

/**
 * What a validated file will do when committed. {@code apply} runs inside the commit transaction
 * and receives the id of the user who uploaded the file.
 */
record ImportPlan(int toCreate, int toUpdate, Applier applier) {

    @FunctionalInterface
    interface Applier {
        void apply(long userId);
    }

    static ImportPlan empty() {
        return new ImportPlan(0, 0, userId -> { });
    }
}
