package com.github.xingjianluo.contentaddressedobjectstorejava.storage;

/** Supplier that may throw a checked {@link StorageException}. */
@FunctionalInterface
interface ThrowingSupplier<T> {
    T get() throws StorageException;
}
