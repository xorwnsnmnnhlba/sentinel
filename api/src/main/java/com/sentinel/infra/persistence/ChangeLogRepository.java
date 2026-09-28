package com.sentinel.infra.persistence;

import com.sentinel.domain.ChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeLogRepository extends JpaRepository<ChangeLog, Long>, ChangeLogQueries {

}
