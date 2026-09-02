package com.development.agent.util;

public class PrimeUtil {

    public static boolean isPrime(int number) {
        if (number <= 1) {
            return false;
        }
        if (number <= 3) {
            return true;
        }
        if (number % 2 == 0 || number % 3 == 0) {
            return false;
        }
        for (int i = 5; i * i <= number; i += 6) {
            if (number % i == 0 || number % (i + 2) == 0) {
                return false;
            }
        }
        return true;
    }

    public static int[] findPrimesInRange(int start, int end) {
        java.util.List<Integer> primes = new java.util.ArrayList<>();
        for (int i = start; i <= end; i++) {
            if (isPrime(i)) {
                primes.add(i);
            }
        }
        return primes.stream().mapToInt(Integer::intValue).toArray();
    }

    public static int nthPrime(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        int count = 0;
        int num = 1;
        while (count < n) {
            num++;
            if (isPrime(num)) {
                count++;
            }
        }
        return num;
    }

    public static void main(String[] args) {
        System.out.println("Is 29 prime? " + isPrime(29));
        System.out.println("Primes between 1-50: ");
        for (int p : findPrimesInRange(1, 50)) {
            System.out.print(p + " ");
        }
        System.out.println("\n10th prime: " + nthPrime(10));
    }
}
