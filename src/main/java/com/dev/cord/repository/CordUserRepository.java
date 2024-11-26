package com.dev.cord.repository;

import com.dev.cord.model.CordUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CordUserRepository extends JpaRepository<CordUser, Long> {
    CordUser findByEmail(String email);
}
