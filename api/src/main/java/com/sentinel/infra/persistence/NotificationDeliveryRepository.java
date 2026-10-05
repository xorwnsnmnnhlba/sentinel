package com.sentinel.infra.persistence;

import java.time.Instant;

import com.sentinel.domain.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryRepository
		extends JpaRepository<NotificationDelivery, Long>, NotificationDeliveryQueries {

	/** 보관기간이 지난 기록 삭제. 하루 수십 건 수준이라 한 번에 삭제 */
	@Modifying
	@Query("delete from NotificationDelivery d where d.sentAt < :cutoff")
	int deleteSentBefore(@Param("cutoff") Instant cutoff);

}
