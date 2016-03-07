/* 
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
/**
 * Author:  skraffmi
 * Created: Mar 4, 2016
 */

alter table
datasetversion
drop column availabilitystatus, 
drop column citationrequirements, 
drop column conditions, 
drop column confidentialitydeclaration, 
drop column contactforaccess, 
drop column depositorrequirements, 
drop column disclaimer, 
drop column fileaccessrequest, 
drop column license, 
drop column originalarchive, 
drop column restrictions, 
drop column sizeofcollection, 
drop column specialpermissions, 
drop column studycompletion, 
drop column termsofaccess, 
drop column termsofuse;
CREATE UNIQUE INDEX index_authenticateduser_lower_email ON authenticateduser (lower(email));
CREATE UNIQUE INDEX index_builtinuser_lower_email ON builtinuser (lower(email));
